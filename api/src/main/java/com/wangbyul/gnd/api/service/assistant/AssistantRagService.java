package com.wangbyul.gnd.api.service.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.AssistantRagProperties;
import com.wangbyul.gnd.api.dto.AssistantRagInsightDto;
import com.wangbyul.gnd.api.service.SystemFeatureToggleService;
import com.wangbyul.gnd.core.domain.AssistantRagAuditLogEntity;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.repository.AssistantRagAuditLogRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 경량 RAG/LLM 보조 분석 서비스.
 *
 * 규칙 엔진 결과를 덮어쓰지 않고 설명/주의/근거 요약 보조만 수행하며,
 * timeout/fallback/circuit breaker를 통해 장애 전파를 차단한다.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class AssistantRagService {

    private final AssistantRagContextBuilderService contextBuilderService;
    private final AssistantRagModelClient modelClient;
    private final AssistantRagAuditLogRepository assistantRagAuditLogRepository;
    private final SystemFeatureToggleService systemFeatureToggleService;
    private final AssistantRagProperties properties;
    private final ObjectMapper objectMapper;

    private final Deque<Boolean> errorWindow = new ArrayDeque<>();
    private final Deque<Boolean> latencyWindow = new ArrayDeque<>();
    private int consecutiveFailures = 0;
    private long circuitOpenUntilEpochMs = 0L;

    public AssistantRagService(
            AssistantRagContextBuilderService contextBuilderService,
            AssistantRagModelClient modelClient,
            AssistantRagAuditLogRepository assistantRagAuditLogRepository,
            SystemFeatureToggleService systemFeatureToggleService,
            AssistantRagProperties properties,
            ObjectMapper objectMapper) {
        this.contextBuilderService = contextBuilderService;
        this.modelClient = modelClient;
        this.assistantRagAuditLogRepository = assistantRagAuditLogRepository;
        this.systemFeatureToggleService = systemFeatureToggleService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 종목 상세 보조 설명 생성.
     */
    @Transactional
    public SignalDetailAssistResult assistSignalDetail(
            TradingSignalEntity signal,
            AssetUniverseEntity asset,
            List<String> riskChecks,
            Map<String, Object> scalpBreakdown,
            boolean requestEnabled) {
        if (signal == null) {
            return new SignalDetailAssistResult(disabledInsight("SIGNAL_NOT_FOUND"), null);
        }

        AssistantRagContextBuilderService.SignalDetailRagContext context;
        try {
            context = contextBuilderService.buildSignalDetailContext(signal, asset, riskChecks, scalpBreakdown);
        } catch (Exception ex) {
            String traceId = effectiveTraceId(signal.getTraceId());
            AssistantRagInsightDto fallback = AssistantRagInsightDto.builder()
                    .enabled(true)
                    .applied(true)
                    .fallbackApplied(true)
                    .circuitOpen(isCircuitOpen())
                    .source("RULE_FALLBACK")
                    .summary("RAG 컨텍스트 생성 오류로 규칙 기반 상세 정보만 사용합니다.")
                    .evidenceBullets(List.of("규칙엔진 결과는 유지됩니다."))
                    .cautionBullets(List.of("보조 설명 컨텍스트 생성 실패"))
                    .missingDataBullets(List.of())
                    .changeTriggers(List.of("컨텍스트 빌드 오류가 해소되면 보조 설명이 복구됩니다."))
                    .explainText("컨텍스트 빌더 예외를 차단하고 fallback 처리되었습니다.")
                    .ruleEngineAction(signal.getAction() == null ? "WATCH" : signal.getAction().name())
                    .ruleEngineActionLocked(true)
                    .promptVersion(properties.getPromptVersion())
                    .modelVersion(properties.getModelVersion())
                    .latencyMsTotal(0L)
                    .errorCode("CONTEXT_BUILD_ERROR")
                    .build();
            saveAudit("SIGNAL_DETAIL", signal.getId(), signal.getAssetCode(), traceId, "[]",
                    fallback.getModelVersion(), fallback.getPromptVersion(), 0L, true, false,
                    "CONTEXT_BUILD_ERROR", toJson(insightMap(fallback)));
            return new SignalDetailAssistResult(fallback, "[]");
        }
        String traceId = effectiveTraceId(signal.getTraceId());
        String baseAction = String.valueOf(context.signalSummary().getOrDefault("action", "WATCH"));

        if (!requestEnabled || !properties.isSignalDetailEnabled() || !properties.isEnabled()) {
            AssistantRagInsightDto insight = disabledInsight(requestEnabled ? "ASSISTANT_DISABLED" : "ASSISTANT_REQUEST_OFF");
            saveAudit("SIGNAL_DETAIL", signal.getId(), signal.getAssetCode(), traceId, context.contextRefsJson(),
                    insight.getModelVersion(), insight.getPromptVersion(), 0L, true, true,
                    insight.getErrorCode(), toJson(insightMap(insight)));
            return new SignalDetailAssistResult(insight, context.contextRefsJson());
        }

        boolean toggleEnabled = systemFeatureToggleService.isFeatureEnabled(
                "RAG_ASSISTANT",
                asset == null ? signal.getCountry() : asset.getCountry(),
                signal.getTheme(),
                signal.getAssetCode());
        if (!toggleEnabled) {
            AssistantRagInsightDto insight = disabledInsight("RAG_ASSISTANT_OFF");
            saveAudit("SIGNAL_DETAIL", signal.getId(), signal.getAssetCode(), traceId, context.contextRefsJson(),
                    insight.getModelVersion(), insight.getPromptVersion(), 0L, true, true,
                    insight.getErrorCode(), toJson(insightMap(insight)));
            return new SignalDetailAssistResult(insight, context.contextRefsJson());
        }

        if (isCircuitOpen()) {
            AssistantRagInsightDto fallback = signalDetailFallback(context, baseAction, true, "CIRCUIT_OPEN");
            saveAudit("SIGNAL_DETAIL", signal.getId(), signal.getAssetCode(), traceId, context.contextRefsJson(),
                    fallback.getModelVersion(), fallback.getPromptVersion(), 0L, true, true,
                    "CIRCUIT_OPEN", toJson(insightMap(fallback)));
            return new SignalDetailAssistResult(fallback, context.contextRefsJson());
        }

        long startedAt = System.nanoTime();
        try {
            AssistantRagModelClient.SignalDetailModelOutput output = executeWithTimeout(
                    () -> modelClient.generateSignalDetailSummary(context),
                    properties.getTimeoutMillis());
            SignalValidation validation = validateSignalOutput(output, baseAction, context);
            long latencyMs = elapsedMs(startedAt);
            boolean latencyBreach = latencyMs > Math.max(10L, properties.getLatencyWarningMillis());
            if (!validation.valid()) {
                recordCircuitOutcome(true, latencyBreach);
                AssistantRagInsightDto fallback = signalDetailFallback(context, baseAction, isCircuitOpen(), validation.errorCode());
                saveAudit("SIGNAL_DETAIL", signal.getId(), signal.getAssetCode(), traceId, context.contextRefsJson(),
                        fallback.getModelVersion(), fallback.getPromptVersion(), latencyMs, true, true,
                        validation.errorCode(), toJson(insightMap(fallback)));
                return new SignalDetailAssistResult(fallback, context.contextRefsJson());
            }
            recordCircuitOutcome(false, latencyBreach);
            AssistantRagInsightDto result = toSignalInsight(output, latencyMs, false, false, null);
            saveAudit("SIGNAL_DETAIL", signal.getId(), signal.getAssetCode(), traceId, context.contextRefsJson(),
                    result.getModelVersion(), result.getPromptVersion(), latencyMs, false, true,
                    null, toJson(insightMap(result)));
            return new SignalDetailAssistResult(result, context.contextRefsJson());
        } catch (TimeoutException timeoutException) {
            return signalDetailErrorFallback(signal, context, traceId, baseAction, startedAt, "TIMEOUT", true);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return signalDetailErrorFallback(signal, context, traceId, baseAction, startedAt, "INTERRUPTED", false);
        } catch (Exception exception) {
            return signalDetailErrorFallback(signal, context, traceId, baseAction, startedAt, "MODEL_ERROR", false);
        }
    }

    /**
     * 관리자 trace 상세용 사람친화적 요약 생성.
     */
    @Transactional
    public Map<String, Object> assistTraceDetail(String traceId, Map<String, Object> traceData, boolean requestEnabled) {
        AssistantRagContextBuilderService.TraceDetailRagContext context;
        try {
            context = contextBuilderService.buildTraceDetailContext(traceId, traceData);
        } catch (Exception ex) {
            String effectiveTraceId = effectiveTraceId(traceId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enabled", true);
            result.put("applied", true);
            result.put("fallback_applied", true);
            result.put("circuit_open", isCircuitOpen());
            result.put("source", "RULE_FALLBACK");
            result.put("summary", "RAG trace 컨텍스트 생성 오류로 fallback 요약만 제공합니다.");
            result.put("highlights", List.of());
            result.put("cautions", List.of("컨텍스트 빌더 예외 발생"));
            result.put("explain_text", "trace 원본 데이터는 유지됩니다.");
            result.put("prompt_version", properties.getPromptVersion());
            result.put("model_version", properties.getModelVersion());
            result.put("latency_ms_total", 0L);
            result.put("error_code", "CONTEXT_BUILD_ERROR");
            result.put("rag_context_refs_json", "[]");
            saveAudit("TRACE_DETAIL", null, null, effectiveTraceId, "[]",
                    properties.getModelVersion(), properties.getPromptVersion(), 0L, true, false,
                    "CONTEXT_BUILD_ERROR", toJson(result));
            return result;
        }
        String effectiveTraceId = effectiveTraceId(traceId);

        if (!requestEnabled || !properties.isTraceDetailEnabled() || !properties.isEnabled()) {
            Map<String, Object> disabled = traceFallback(context, true, requestEnabled ? "ASSISTANT_DISABLED" : "ASSISTANT_REQUEST_OFF");
            disabled.put("enabled", false);
            disabled.put("applied", false);
            disabled.put("source", "DISABLED");
            saveAudit("TRACE_DETAIL", null, null, effectiveTraceId, context.contextRefsJson(),
                    properties.getModelVersion(), properties.getPromptVersion(), 0L, true, true,
                    String.valueOf(disabled.get("error_code")), toJson(disabled));
            return disabled;
        }
        if (!systemFeatureToggleService.isFeatureEnabled("RAG_ASSISTANT", null, null, null)) {
            Map<String, Object> disabled = traceFallback(context, true, "RAG_ASSISTANT_OFF");
            disabled.put("enabled", false);
            disabled.put("applied", false);
            disabled.put("source", "DISABLED");
            saveAudit("TRACE_DETAIL", null, null, effectiveTraceId, context.contextRefsJson(),
                    properties.getModelVersion(), properties.getPromptVersion(), 0L, true, true,
                    "RAG_ASSISTANT_OFF", toJson(disabled));
            return disabled;
        }
        if (isCircuitOpen()) {
            Map<String, Object> fallback = traceFallback(context, true, "CIRCUIT_OPEN");
            saveAudit("TRACE_DETAIL", null, null, effectiveTraceId, context.contextRefsJson(),
                    properties.getModelVersion(), properties.getPromptVersion(), 0L, true, true,
                    "CIRCUIT_OPEN", toJson(fallback));
            return fallback;
        }

        long startedAt = System.nanoTime();
        try {
            AssistantRagModelClient.TraceDetailModelOutput output = executeWithTimeout(
                    () -> modelClient.generateTraceDetailSummary(context),
                    properties.getTimeoutMillis());
            TraceValidation validation = validateTraceOutput(output, context);
            long latencyMs = elapsedMs(startedAt);
            boolean latencyBreach = latencyMs > Math.max(10L, properties.getLatencyWarningMillis());
            if (!validation.valid()) {
                recordCircuitOutcome(true, latencyBreach);
                Map<String, Object> fallback = traceFallback(context, isCircuitOpen(), validation.errorCode());
                saveAudit("TRACE_DETAIL", null, null, effectiveTraceId, context.contextRefsJson(),
                        properties.getModelVersion(), properties.getPromptVersion(), latencyMs, true, true,
                        validation.errorCode(), toJson(fallback));
                return fallback;
            }
            recordCircuitOutcome(false, latencyBreach);
            Map<String, Object> result = toTraceSummary(output, context, latencyMs, false, false, null);
            saveAudit("TRACE_DETAIL", null, null, effectiveTraceId, context.contextRefsJson(),
                    properties.getModelVersion(), properties.getPromptVersion(), latencyMs, false, true,
                    null, toJson(result));
            return result;
        } catch (TimeoutException timeoutException) {
            return traceErrorFallback(context, effectiveTraceId, startedAt, "TIMEOUT", true);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return traceErrorFallback(context, effectiveTraceId, startedAt, "INTERRUPTED", false);
        } catch (Exception exception) {
            return traceErrorFallback(context, effectiveTraceId, startedAt, "MODEL_ERROR", false);
        }
    }

    private SignalDetailAssistResult signalDetailErrorFallback(
            TradingSignalEntity signal,
            AssistantRagContextBuilderService.SignalDetailRagContext context,
            String traceId,
            String baseAction,
            long startedAt,
            String errorCode,
            boolean latencyBreach) {
        long latencyMs = elapsedMs(startedAt);
        recordCircuitOutcome(true, latencyBreach);
        AssistantRagInsightDto fallback = signalDetailFallback(context, baseAction, isCircuitOpen(), errorCode);
        saveAudit("SIGNAL_DETAIL", signal.getId(), signal.getAssetCode(), traceId, context.contextRefsJson(),
                fallback.getModelVersion(), fallback.getPromptVersion(), latencyMs, true, false,
                errorCode, toJson(insightMap(fallback)));
        return new SignalDetailAssistResult(fallback, context.contextRefsJson());
    }

    private Map<String, Object> traceErrorFallback(
            AssistantRagContextBuilderService.TraceDetailRagContext context,
            String traceId,
            long startedAt,
            String errorCode,
            boolean latencyBreach) {
        long latencyMs = elapsedMs(startedAt);
        recordCircuitOutcome(true, latencyBreach);
        Map<String, Object> result = traceFallback(context, isCircuitOpen(), errorCode);
        saveAudit("TRACE_DETAIL", null, null, traceId, context.contextRefsJson(),
                properties.getModelVersion(), properties.getPromptVersion(), latencyMs, true, false,
                errorCode, toJson(result));
        return result;
    }

    private AssistantRagInsightDto toSignalInsight(
            AssistantRagModelClient.SignalDetailModelOutput output,
            long latencyMs,
            boolean fallbackApplied,
            boolean circuitOpen,
            String errorCode) {
        return AssistantRagInsightDto.builder()
                .enabled(true)
                .applied(true)
                .fallbackApplied(fallbackApplied)
                .circuitOpen(circuitOpen)
                .source(fallbackApplied ? "RULE_FALLBACK" : "RAG_MODEL")
                .summary(trim(output.summary(), 500))
                .evidenceBullets(limitList(sanitizeBullets(output.evidenceBullets(), false), 6))
                .cautionBullets(limitList(sanitizeBullets(output.cautionBullets(), false), 6))
                .missingDataBullets(limitList(sanitizeBullets(output.missingDataBullets(), false), 6))
                .changeTriggers(limitList(sanitizeBullets(output.changeTriggers(), false), 6))
                .explainText(trim(output.explainText(), 800))
                .ruleEngineAction(output.ruleEngineAction())
                .ruleEngineActionLocked(Boolean.TRUE.equals(output.ruleEngineActionLocked()))
                .promptVersion(properties.getPromptVersion())
                .modelVersion(properties.getModelVersion())
                .latencyMsTotal(latencyMs)
                .errorCode(errorCode)
                .build();
    }

    private AssistantRagInsightDto signalDetailFallback(
            AssistantRagContextBuilderService.SignalDetailRagContext context,
            String baseAction,
            boolean circuitOpen,
            String errorCode) {
        Map<String, Object> signalSummary = context.signalSummary();
        String dataState = String.valueOf(signalSummary.getOrDefault("data_state", ""));
        List<String> evidence = new ArrayList<>();
        evidence.add("규칙엔진 기본 판단 유지: " + baseAction);
        evidence.add("연결 뉴스 " + context.recentNews().size() + "건, 리스크체크 " + context.riskChecks().size() + "건 기준 요약");
        if (!context.quoteSummary().isEmpty()) {
            evidence.add("시세 스냅샷/바 요약 기반 보조 설명(모델 fallback)");
        }

        List<String> cautions = new ArrayList<>();
        cautions.add("RAG 보조 계층 장애/지연으로 규칙 기반 fallback 설명이 사용되었습니다.");
        if (!dataState.isBlank()) {
            cautions.add("데이터 상태: " + dataState);
        }
        if (Boolean.TRUE.equals(signalSummary.get("blocked"))) {
            cautions.add("리스크 차단 신호이므로 주문 허용 의미가 아닙니다.");
        }

        List<String> missing = new ArrayList<>();
        if (context.recentNews().isEmpty()) {
            missing.add("연결 뉴스 부족");
        }
        if (Boolean.TRUE.equals(context.quoteSummary().get("missing_quote"))) {
            missing.add("최신 시세 스냅샷 없음");
        }
        if (Boolean.TRUE.equals(context.barSummary().get("insufficient_bars"))) {
            missing.add("바 데이터 표본 부족");
        }

        return AssistantRagInsightDto.builder()
                .enabled(true)
                .applied(true)
                .fallbackApplied(true)
                .circuitOpen(circuitOpen)
                .source("RULE_FALLBACK")
                .summary(trim(blankAs(context.assetName(), context.assetCode())
                        + "의 규칙엔진 기본 판단(" + baseAction
                        + ")을 유지하며, 현재는 RAG 보조 요약 대신 규칙 기반 fallback 설명을 제공합니다.", 500))
                .evidenceBullets(limitList(evidence, 6))
                .cautionBullets(limitList(cautions, 6))
                .missingDataBullets(limitList(missing, 6))
                .changeTriggers(List.of(
                        "RAG 보조 계층이 복구되면 더 자세한 자연어 근거 요약이 제공됩니다.",
                        "신규 뉴스/시세 데이터 유입 시 fallback 설명도 갱신됩니다."))
                .explainText("규칙 엔진 결과를 변경하지 않고 뉴스/시세/리스크 요약만 보조합니다. (fallback 모드)")
                .ruleEngineAction(baseAction)
                .ruleEngineActionLocked(true)
                .promptVersion(properties.getPromptVersion())
                .modelVersion(properties.getModelVersion())
                .latencyMsTotal(0L)
                .errorCode(errorCode)
                .build();
    }

    private Map<String, Object> traceFallback(
            AssistantRagContextBuilderService.TraceDetailRagContext context,
            boolean circuitOpen,
            String errorCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("applied", true);
        result.put("fallback_applied", true);
        result.put("circuit_open", circuitOpen);
        result.put("source", "RULE_FALLBACK");
        result.put("summary", "trace 상세는 fallback 요약 모드입니다. 수집/시그널/토글 카운트를 기준으로 빠른 운영 요약만 제공합니다.");
        result.put("highlights", List.of(
                "counts=" + context.counts(),
                "market_summary=" + context.marketSummary(),
                "signal_summary=" + context.signalSummary()));
        result.put("cautions", List.of(
                "RAG 보조 계층 지연/오류/비활성화로 fallback 요약이 사용되었습니다.",
                "세부 원인은 trace 상세의 개별 행(provider_audits/signal_audits/strategy_runs)을 직접 확인하십시오."));
        result.put("explain_text", "규칙/감사 원본 데이터는 유지되며, 이 요약은 운영 확인용 보조 텍스트입니다.");
        result.put("prompt_version", properties.getPromptVersion());
        result.put("model_version", properties.getModelVersion());
        result.put("latency_ms_total", 0L);
        result.put("error_code", errorCode);
        result.put("rag_context_refs_json", context.contextRefsJson());
        return result;
    }

    private Map<String, Object> toTraceSummary(
            AssistantRagModelClient.TraceDetailModelOutput output,
            AssistantRagContextBuilderService.TraceDetailRagContext context,
            long latencyMs,
            boolean fallbackApplied,
            boolean circuitOpen,
            String errorCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("applied", true);
        result.put("fallback_applied", fallbackApplied);
        result.put("circuit_open", circuitOpen);
        result.put("source", fallbackApplied ? "RULE_FALLBACK" : "RAG_MODEL");
        result.put("summary", trim(output.summary(), 700));
        result.put("highlights", limitList(sanitizeBullets(output.highlights(), false), 8));
        result.put("cautions", limitList(sanitizeBullets(output.cautions(), false), 8));
        result.put("explain_text", trim(output.explainText(), 900));
        result.put("prompt_version", properties.getPromptVersion());
        result.put("model_version", properties.getModelVersion());
        result.put("latency_ms_total", latencyMs);
        result.put("error_code", errorCode);
        result.put("rag_context_refs_json", context.contextRefsJson());
        return result;
    }

    private SignalValidation validateSignalOutput(
            AssistantRagModelClient.SignalDetailModelOutput output,
            String baseAction,
            AssistantRagContextBuilderService.SignalDetailRagContext context) {
        if (output == null) {
            return new SignalValidation(false, "OUTPUT_NULL");
        }
        if (isBlank(output.summary()) || isBlank(output.explainText())) {
            return new SignalValidation(false, "OUTPUT_SCHEMA_INVALID");
        }
        if (!Boolean.TRUE.equals(output.ruleEngineActionLocked())) {
            return new SignalValidation(false, "RULE_ENGINE_ACTION_UNLOCKED");
        }
        if (!baseAction.equalsIgnoreCase(blankAs(output.ruleEngineAction(), ""))) {
            return new SignalValidation(false, "RULE_ENGINE_ACTION_MISMATCH");
        }
        String dataState = String.valueOf(context.signalSummary().getOrDefault("data_state", ""));
        if (properties.getDataGapStates().stream().anyMatch(s -> s.equalsIgnoreCase(dataState))) {
            List<String> cautions = output.cautionBullets() == null ? List.of() : output.cautionBullets();
            boolean hasDataGapCaution = cautions.stream().anyMatch(text -> text != null && text.contains("데이터"));
            if (!hasDataGapCaution) {
                return new SignalValidation(false, "DATA_GAP_CAUTION_MISSING");
            }
        }
        return new SignalValidation(true, null);
    }

    private TraceValidation validateTraceOutput(
            AssistantRagModelClient.TraceDetailModelOutput output,
            AssistantRagContextBuilderService.TraceDetailRagContext context) {
        if (output == null) {
            return new TraceValidation(false, "OUTPUT_NULL");
        }
        if (isBlank(output.summary()) || isBlank(output.explainText())) {
            return new TraceValidation(false, "OUTPUT_SCHEMA_INVALID");
        }
        if ((output.highlights() == null || output.highlights().isEmpty()) && !context.counts().isEmpty()) {
            return new TraceValidation(false, "TRACE_HIGHLIGHT_MISSING");
        }
        return new TraceValidation(true, null);
    }

    private <T> T executeWithTimeout(TaskSupplier<T> supplier, long timeoutMillis)
            throws TimeoutException, InterruptedException {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        try {
            return future.get(Math.max(50L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            if (cause instanceof RuntimeException runtime && runtime.getCause() instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new RuntimeException(cause == null ? executionException : cause);
        }
    }

    private synchronized boolean isCircuitOpen() {
        return System.currentTimeMillis() < circuitOpenUntilEpochMs;
    }

    private synchronized void recordCircuitOutcome(boolean error, boolean latencyBreach) {
        consecutiveFailures = error ? consecutiveFailures + 1 : 0;
        appendWindow(errorWindow, error, Math.max(5, properties.getCircuitWindowSize()));
        appendWindow(latencyWindow, latencyBreach, Math.max(5, properties.getCircuitWindowSize()));
        double errorRate = rate(errorWindow);
        double latencyRate = rate(latencyWindow);
        boolean shouldOpen = consecutiveFailures >= Math.max(1, properties.getCircuitConsecutiveFailureThreshold())
                || (errorWindow.size() >= Math.max(5, properties.getCircuitWindowSize() / 2)
                        && errorRate >= properties.getCircuitErrorRateThreshold())
                || (latencyWindow.size() >= Math.max(5, properties.getCircuitWindowSize() / 2)
                        && latencyRate >= properties.getCircuitLatencyRateThreshold());
        if (shouldOpen) {
            circuitOpenUntilEpochMs = System.currentTimeMillis() + Math.max(5, properties.getCircuitOpenSeconds()) * 1000L;
        }
    }

    private void appendWindow(Deque<Boolean> deque, boolean value, int maxSize) {
        deque.addLast(value);
        while (deque.size() > maxSize) {
            deque.removeFirst();
        }
    }

    private double rate(Deque<Boolean> deque) {
        if (deque.isEmpty()) {
            return 0d;
        }
        long positives = deque.stream().filter(Boolean.TRUE::equals).count();
        return positives / (double) deque.size();
    }

    private void saveAudit(
            String scope,
            String signalId,
            String assetCode,
            String traceId,
            String ragContextRefsJson,
            String modelVersion,
            String promptVersion,
            Long latencyMs,
            boolean fallbackApplied,
            boolean success,
            String errorCode,
            String outputJson) {
        AssistantRagAuditLogEntity row = new AssistantRagAuditLogEntity();
        row.setRequestScope(scope);
        row.setRequestKey("SIGNAL_DETAIL".equals(scope) ? signalId : traceId);
        row.setSignalId(signalId);
        row.setAssetCode(assetCode);
        row.setTraceId(traceId);
        row.setModelVersion(modelVersion);
        row.setPromptVersion(promptVersion);
        row.setRagContextRefsJson(blankAs(ragContextRefsJson, "[]"));
        row.setLatencyMsTotal(latencyMs);
        row.setFallbackApplied(fallbackApplied);
        row.setSuccess(success);
        row.setErrorCode(errorCode);
        row.setOutputJson(blankAs(outputJson, "{}"));
        assistantRagAuditLogRepository.save(row);
    }

    private AssistantRagInsightDto disabledInsight(String errorCode) {
        return AssistantRagInsightDto.builder()
                .enabled(false)
                .applied(false)
                .fallbackApplied(true)
                .circuitOpen(isCircuitOpen())
                .source("DISABLED")
                .summary("경량 RAG 보조 설명이 비활성화되어 규칙 기반 상세 정보만 표시합니다.")
                .evidenceBullets(List.of())
                .cautionBullets(List.of("RAG_ASSISTANT 기능이 비활성화되어 있습니다."))
                .missingDataBullets(List.of())
                .changeTriggers(List.of("feature toggle RAG_ASSISTANT를 ON으로 전환하면 보조 설명이 활성화됩니다."))
                .explainText("규칙 엔진 결과는 그대로 유지됩니다.")
                .ruleEngineAction(null)
                .ruleEngineActionLocked(true)
                .promptVersion(properties.getPromptVersion())
                .modelVersion(properties.getModelVersion())
                .latencyMsTotal(0L)
                .errorCode(errorCode)
                .build();
    }

    private Map<String, Object> insightMap(AssistantRagInsightDto insight) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", insight.getEnabled());
        map.put("applied", insight.getApplied());
        map.put("fallback_applied", insight.getFallbackApplied());
        map.put("circuit_open", insight.getCircuitOpen());
        map.put("source", insight.getSource());
        map.put("summary", insight.getSummary());
        map.put("evidence_bullets", insight.getEvidenceBullets());
        map.put("caution_bullets", insight.getCautionBullets());
        map.put("missing_data_bullets", insight.getMissingDataBullets());
        map.put("change_triggers", insight.getChangeTriggers());
        map.put("explain_text", insight.getExplainText());
        map.put("rule_engine_action", insight.getRuleEngineAction());
        map.put("rule_engine_action_locked", insight.getRuleEngineActionLocked());
        map.put("prompt_version", insight.getPromptVersion());
        map.put("model_version", insight.getModelVersion());
        map.put("latency_ms_total", insight.getLatencyMsTotal());
        map.put("error_code", insight.getErrorCode());
        return map;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private List<String> sanitizeBullets(List<String> rows, boolean requireCautiousTone) {
        List<String> result = new ArrayList<>();
        if (rows != null) {
            for (String row : rows) {
                String text = trim(row, 240);
                if (text == null) {
                    continue;
                }
                if (requireCautiousTone || hasAssertionText(text)) {
                    text = softenAssertion(text);
                }
                result.add(text);
            }
        }
        return result;
    }

    private boolean hasAssertionText(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.toLowerCase();
        return normalized.contains("확정") || normalized.contains("반드시") || normalized.contains("100%");
    }

    private String softenAssertion(String text) {
        if (text == null) {
            return null;
        }
        return text.startsWith("주의:") ? text : "주의: " + text;
    }

    private List<String> limitList(List<String> rows, int maxSize) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream().filter(v -> v != null && !v.isBlank()).limit(Math.max(1, maxSize)).toList();
    }

    private long elapsedMs(long startedAtNano) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedAtNano));
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isBlank()) {
            return null;
        }
        if (text.length() <= Math.max(1, max)) {
            return text;
        }
        return text.substring(0, Math.max(1, max) - 3) + "...";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String blankAs(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private String effectiveTraceId(String preferred) {
        if (!isBlank(preferred)) {
            return preferred;
        }
        String trace = MDC.get("trace_id");
        return isBlank(trace) ? UUID.randomUUID().toString() : trace;
    }

    @FunctionalInterface
    private interface TaskSupplier<T> {
        T get() throws Exception;
    }

    private record SignalValidation(boolean valid, String errorCode) {
    }

    private record TraceValidation(boolean valid, String errorCode) {
    }

    /**
     * 종목 상세 API에 주입할 보조 설명 결과.
     */
    public record SignalDetailAssistResult(AssistantRagInsightDto insight, String ragContextRefsJson) {
    }
}

package com.wangbyul.gnd.api.service.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.dto.BuyLockStatusDto;
import com.wangbyul.gnd.api.dto.PaperTradeRiskDto;
import com.wangbyul.gnd.api.dto.SignalDetailDto;
import com.wangbyul.gnd.api.dto.TradingSignalViewDto;
import com.wangbyul.gnd.api.service.AdminDiagnosticsService;
import com.wangbyul.gnd.api.service.SystemFeatureToggleService;
import com.wangbyul.gnd.api.service.signal.RiskPolicyService;
import com.wangbyul.gnd.api.service.signal.TradingSignalEngineService;
import com.wangbyul.gnd.core.domain.AssistantRagAuditLogEntity;
import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
import com.wangbyul.gnd.core.repository.AssistantRagAuditLogRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.NewsAssetLinkRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 비서 화면용 대시보드 집계 서비스.
 *
 * 기존 뉴스 홈/관리자 진단 API와 분리된 읽기 전용 집계 응답을 제공한다.
 * 규칙 엔진 결과를 변경하지 않고 화면 표시용 상태/요약만 조합한다.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
@RequiredArgsConstructor
public class AssistantDashboardService {

    private static final List<String> STRATEGY_ORDER = List.of("SCALP", "SWING", "CHART_RESPONSE", "DISCOVERY");

    private final TradingSignalEngineService tradingSignalEngineService;
    private final RiskPolicyService riskPolicyService;
    private final AdminDiagnosticsService adminDiagnosticsService;
    private final SystemFeatureToggleService systemFeatureToggleService;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final NewsAssetLinkRepository newsAssetLinkRepository;
    private final AssistantRagAuditLogRepository assistantRagAuditLogRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.trade.live-enabled:false}")
    private boolean liveTradeEnabledProperty;

    @Transactional(readOnly = true)
    public Map<String, Object> buildDashboard(String country, String theme, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 12));
        String scopeCountry = safeString(country).toUpperCase(Locale.ROOT);
        String scopeTheme = safeString(theme);

        List<TradingSignalViewDto> scalp = tradingSignalEngineService.getScalpSignals(scopeCountry, scopeTheme, safeLimit);
        List<TradingSignalViewDto> swing = tradingSignalEngineService.getSwingSignals(scopeCountry, scopeTheme, safeLimit);
        List<TradingSignalViewDto> discovery = tradingSignalEngineService.getDiscoverySignals(scopeCountry, scopeTheme, safeLimit);
        TradingSignalViewDto position = resolvePositionSignal(scalp, swing, discovery);
        StrategyRows rows = rebalanceStrategyRows(scalp, swing, discovery, position, safeLimit);
        scalp = rows.scalp();
        swing = rows.swing();
        discovery = rows.discovery();
        position = rows.position();

        List<TradingSignalViewDto> mergedCandidates = mergeCandidates(scalp, swing, discovery, position);
        List<Map<String, Object>> watchlist = buildWatchlistCards(mergedCandidates);
        String selectedSignalId = watchlist.stream()
                .map(row -> stringValue(row.get("signal_id")))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
        String selectedAssetCode = watchlist.stream()
                .map(row -> stringValue(row.get("asset_code")))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");

        PaperTradeRiskDto riskPanel = riskPolicyService.portfolioRisk(null);
        List<BuyLockStatusDto> locks = riskPolicyService.buyLocks();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("country", scopeCountry);
        data.put("theme", scopeTheme);
        data.put("theme_code", tradingSignalEngineService.normalizeThemeForApi(scopeTheme));
        data.put("strategy_order", STRATEGY_ORDER);
        data.put("status_bar", buildStatusBar(scopeCountry, scopeTheme, mergedCandidates, watchlist));
        data.put("strategies", buildStrategiesPayload(scalp, swing, discovery, position));
        data.put("strategy_diversity", rows.summary());
        data.put("watchlist", watchlist);
        data.put("risk_panel", riskPanel);
        data.put("locks", locks);
        data.put("selected_signal_id", selectedSignalId);
        data.put("selected_asset_code", selectedAssetCode);
        data.put("generated_at", OffsetDateTime.now());
        return data;
    }

    /**
     * AI 비서 Q&A (가능 범위: 현재 선택 종목 상세 근거 기반 요약 질의응답).
     *
     * 비용 통제를 위해 추가 모델 호출은 하지 않고 규칙 엔진 상세 DTO + 최근 SIGNAL_DETAIL RAG 감사로그를 재사용한다.
     * 규칙 엔진 판단(action)은 변경하지 않으며 설명/요약만 보강한다.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> answerQuestion(String signalId, String question) {
        String safeSignalId = safeString(signalId);
        String safeQuestion = safeString(question);
        if (safeSignalId.isBlank()) {
            throw new IllegalArgumentException("signal_id is required");
        }
        if (safeQuestion.isBlank()) {
            throw new IllegalArgumentException("q is required");
        }

        List<AssistantRagAuditLogEntity> signalRagAudits = assistantRagAuditLogRepository.findTop100BySignalIdOrderByCreatedAtDesc(safeSignalId)
                .stream()
                .filter(row -> "SIGNAL_DETAIL".equalsIgnoreCase(safeString(row.getRequestScope())))
                .toList();
        AssistantRagAuditLogEntity ragAudit = signalRagAudits.stream()
                .filter(row -> !"ASSISTANT_REQUEST_OFF".equalsIgnoreCase(safeString(row.getErrorCode())))
                .findFirst()
                .orElse(signalRagAudits.stream().findFirst().orElse(null));
        SignalDetailDto detail = tradingSignalEngineService.getSignalDetail(safeSignalId, false);
        if (detail == null) {
            throw new IllegalArgumentException("signal detail not found: " + safeSignalId);
        }

        Map<String, Object> breakdown = parseJsonMap(detail.getProbabilityReasonBreakdownJson());
        List<Map<String, Object>> detailRagRefs = parseJsonMapList(detail.getRagContextRefsJson());
        Map<String, Object> riskGuide = detail.getRiskGuidance() == null ? Map.of() : detail.getRiskGuidance();
        List<String> newsEvidence = limitLines(detail.getNewsEvidence(), 4);
        List<String> chartEvidence = limitLines(detail.getChartEvidence(), 4);
        List<String> volumeEvidence = limitLines(detail.getVolumeEvidence(), 3);
        List<String> riskEvidence = limitLines(detail.getRiskEvidence(), 4);
        List<String> missing = limitLines(detail.getMissingRequirements(), 4);
        List<String> changes = limitLines(detail.getChangeConditions(), 4);

        Map<String, Object> ragAuditOutput = ragAudit == null ? Map.of() : parseJsonMap(ragAudit.getOutputJson());
        List<Map<String, Object>> ragAuditRefs = ragAudit == null ? List.of() : parseJsonMapList(ragAudit.getRagContextRefsJson());
        List<String> ragEvidence = limitLines(stringList(ragAuditOutput.get("evidence_bullets")), 4);
        List<String> ragCautions = limitLines(stringList(ragAuditOutput.get("caution_bullets")), 4);
        String ragSummary = safeString(ragAuditOutput.get("summary"));

        String questionType = classifyQuestionType(safeQuestion);
        String answerText = buildQaAnswerText(
                questionType,
                safeQuestion,
                detail,
                breakdown,
                riskGuide,
                newsEvidence,
                chartEvidence,
                volumeEvidence,
                riskEvidence,
                missing,
                changes,
                ragSummary,
                ragEvidence,
                ragCautions);

        Map<String, Object> ragSupport = new LinkedHashMap<>();
        ragSupport.put("source", ragAudit == null ? "RULE_ONLY" : "RAG_AUDIT_REUSED");
        ragSupport.put("audit_found", ragAudit != null);
        ragSupport.put("fallback_used", ragAudit != null && Boolean.TRUE.equals(ragAudit.getFallbackApplied()));
        ragSupport.put("fallback_reason", ragAudit == null ? "" : safeString(ragAudit.getErrorCode()));
        ragSupport.put("success", ragAudit != null && Boolean.TRUE.equals(ragAudit.getSuccess()));
        ragSupport.put("model_version", ragAudit == null ? "" : safeString(ragAudit.getModelVersion()));
        ragSupport.put("prompt_version", ragAudit == null ? "" : safeString(ragAudit.getPromptVersion()));
        ragSupport.put("latency_ms_total", ragAudit == null ? 0L : (ragAudit.getLatencyMsTotal() == null ? 0L : ragAudit.getLatencyMsTotal()));
        ragSupport.put("summary", ragSummary);
        ragSupport.put("evidence_bullets", ragEvidence);
        ragSupport.put("caution_bullets", ragCautions);
        ragSupport.put("rag_context_ref_count", ragAuditRefs.isEmpty() ? detailRagRefs.size() : ragAuditRefs.size());
        ragSupport.put("rag_context_refs", ragAuditRefs.isEmpty() ? detailRagRefs.stream().limit(6).toList() : ragAuditRefs.stream().limit(6).toList());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("news", newsEvidence);
        evidence.put("chart", chartEvidence);
        evidence.put("volume", volumeEvidence);
        evidence.put("risk", riskEvidence);
        evidence.put("missing_requirements", missing);
        evidence.put("change_conditions", changes);
        evidence.put("rag_context_refs", detailRagRefs.stream().limit(8).toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signal_id", safeString(detail.getSignalId()));
        result.put("asset_code", safeString(detail.getAssetCode()));
        result.put("asset_name", safeString(detail.getAssetName()));
        result.put("question", safeQuestion);
        result.put("question_type", questionType);
        result.put("answer", answerText);
        result.put("current_action", detail.getAction() == null ? "WATCH" : detail.getAction().name());
        result.put("rule_engine_priority", true);
        result.put("rule_engine_action_locked", true);
        result.put("combined_confidence", scale(detail.getCombinedConfidence(), 4));
        result.put("good_news_probability", scale(detail.getGoodNewsProbability(), 4));
        result.put("bad_news_probability", scale(detail.getBadNewsProbability(), 4));
        String detailDataState = safeString(detail.getDataState());
        result.put("data_state", safeString(detailDataState.isBlank() ? breakdown.get("data_state") : detailDataState));
        result.put("blocked_reason", safeString(detail.getBlockedReason()));
        result.put("decision_why", safeString(detail.getDecisionWhy()));
        result.put("risk_guidance", riskGuide);
        result.put("evidence", evidence);
        result.put("rag_support", ragSupport);
        result.put("warnings", List.of(
                "규칙 엔진 판단(action)을 변경하지 않는 설명/요약용 응답입니다.",
                "실주문 결정 전에는 최신 시세/뉴스/토글 상태를 다시 확인하세요."));
        result.put("suggested_questions", suggestedQuestions(detail));
        result.put("generated_at", OffsetDateTime.now());
        return result;
    }

    private TradingSignalViewDto resolvePositionSignal(
            List<TradingSignalViewDto> scalp,
            List<TradingSignalViewDto> swing,
            List<TradingSignalViewDto> discovery) {
        String assetCode = firstPreferredAssetCode(scalp, swing, discovery);
        if (assetCode.isBlank()) {
            return null;
        }
        try {
            return tradingSignalEngineService.getPositionSignal(assetCode);
        } catch (Exception ignored) {
            return null;
        }
    }

    private StrategyRows rebalanceStrategyRows(
            List<TradingSignalViewDto> scalp,
            List<TradingSignalViewDto> swing,
            List<TradingSignalViewDto> discovery,
            TradingSignalViewDto position,
            int maxPerStrategy) {
        int perStrategyLimit = Math.max(1, Math.min(maxPerStrategy, 8));
        Set<String> usedAssets = new LinkedHashSet<>();
        int overlapReused = 0;

        List<TradingSignalViewDto> scalpRows = pickUniqueRows(scalp, usedAssets, perStrategyLimit, true);
        if (!scalpRows.isEmpty() && usedAssets.contains(safeString(scalpRows.get(0).getAssetCode()))) {
            // no-op; 첫 전략은 항상 신규 등록
        }
        List<TradingSignalViewDto> swingRows = pickUniqueRows(swing, usedAssets, perStrategyLimit, true);
        TradingSignalViewDto positionRow = resolvePositionRow(position, scalp, swing, discovery, usedAssets);
        if (positionRow != null && isUsableSignalRow(positionRow)) {
            if (!usedAssets.add(safeString(positionRow.getAssetCode()))) {
                overlapReused++;
            }
        }
        List<TradingSignalViewDto> discoveryRows = pickUniqueRows(discovery, usedAssets, perStrategyLimit, true);

        if (scalpRows.isEmpty() && !scalp.isEmpty()) {
            TradingSignalViewDto fallback = firstUsable(scalp);
            if (fallback != null) {
                scalpRows = List.of(fallback);
                overlapReused++;
            }
        }
        if (swingRows.isEmpty() && !swing.isEmpty()) {
            TradingSignalViewDto fallback = firstUsable(swing);
            if (fallback != null) {
                swingRows = List.of(fallback);
                overlapReused++;
            }
        }
        if (discoveryRows.isEmpty() && !discovery.isEmpty()) {
            TradingSignalViewDto fallback = firstUsable(discovery);
            if (fallback != null) {
                discoveryRows = List.of(fallback);
                overlapReused++;
            }
        }

        int uniqueAssetCount = (int) java.util.stream.Stream.concat(
                        java.util.stream.Stream.concat(
                                scalpRows.stream().map(TradingSignalViewDto::getAssetCode),
                                swingRows.stream().map(TradingSignalViewDto::getAssetCode)),
                        java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(positionRow).filter(Objects::nonNull).map(TradingSignalViewDto::getAssetCode),
                                discoveryRows.stream().map(TradingSignalViewDto::getAssetCode)))
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .count();
        int totalRows = scalpRows.size() + swingRows.size() + discoveryRows.size() + (positionRow == null ? 0 : 1);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_rows", totalRows);
        summary.put("unique_asset_count", uniqueAssetCount);
        summary.put("overlap_reused_count", overlapReused);
        summary.put("scalp_count", scalpRows.size());
        summary.put("swing_count", swingRows.size());
        summary.put("chart_response_count", positionRow == null ? 0 : 1);
        summary.put("discovery_count", discoveryRows.size());

        return new StrategyRows(scalpRows, swingRows, discoveryRows, positionRow, summary);
    }

    private List<TradingSignalViewDto> pickUniqueRows(
            List<TradingSignalViewDto> source,
            Set<String> usedAssets,
            int limit,
            boolean registerUsed) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<TradingSignalViewDto> selected = new ArrayList<>();
        Set<String> localSeen = new LinkedHashSet<>();
        for (TradingSignalViewDto row : source) {
            if (!isUsableSignalRow(row)) {
                continue;
            }
            String assetCode = safeString(row.getAssetCode());
            if (!localSeen.add(assetCode)) {
                continue;
            }
            if (usedAssets.contains(assetCode)) {
                continue;
            }
            selected.add(row);
            if (registerUsed) {
                usedAssets.add(assetCode);
            }
            if (selected.size() >= limit) {
                break;
            }
        }
        return selected;
    }

    private TradingSignalViewDto resolvePositionRow(
            TradingSignalViewDto preferred,
            List<TradingSignalViewDto> scalp,
            List<TradingSignalViewDto> swing,
            List<TradingSignalViewDto> discovery,
            Set<String> usedAssets) {
        if (isUsableSignalRow(preferred) && !usedAssets.contains(safeString(preferred.getAssetCode()))) {
            return preferred;
        }
        for (TradingSignalViewDto row : swing) {
            if (isUsableSignalRow(row) && !usedAssets.contains(safeString(row.getAssetCode()))) {
                return row;
            }
        }
        for (TradingSignalViewDto row : scalp) {
            if (isUsableSignalRow(row) && !usedAssets.contains(safeString(row.getAssetCode()))) {
                return row;
            }
        }
        for (TradingSignalViewDto row : discovery) {
            if (isUsableSignalRow(row) && !usedAssets.contains(safeString(row.getAssetCode()))) {
                return row;
            }
        }
        return isUsableSignalRow(preferred) ? preferred : null;
    }

    private TradingSignalViewDto firstUsable(List<TradingSignalViewDto> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.stream().filter(this::isUsableSignalRow).findFirst().orElse(null);
    }

    private boolean isUsableSignalRow(TradingSignalViewDto row) {
        return row != null && row.getAssetCode() != null && !row.getAssetCode().isBlank();
    }

    private String firstPreferredAssetCode(
            List<TradingSignalViewDto> scalp,
            List<TradingSignalViewDto> swing,
            List<TradingSignalViewDto> discovery) {
        Set<String> scalpCodes = scalp.stream()
                .map(TradingSignalViewDto::getAssetCode)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        for (TradingSignalViewDto row : swing) {
            if (row != null && row.getAssetCode() != null && !scalpCodes.contains(row.getAssetCode())) {
                return row.getAssetCode();
            }
        }
        Set<String> swingCodes = swing.stream()
                .map(TradingSignalViewDto::getAssetCode)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        for (TradingSignalViewDto row : discovery) {
            if (row == null || row.getAssetCode() == null) {
                continue;
            }
            if (!scalpCodes.contains(row.getAssetCode()) && !swingCodes.contains(row.getAssetCode())) {
                return row.getAssetCode();
            }
        }
        if (!scalp.isEmpty() && scalp.get(0) != null && scalp.get(0).getAssetCode() != null) {
            return scalp.get(0).getAssetCode();
        }
        if (!swing.isEmpty() && swing.get(0) != null && swing.get(0).getAssetCode() != null) {
            return swing.get(0).getAssetCode();
        }
        if (!discovery.isEmpty() && discovery.get(0) != null && discovery.get(0).getAssetCode() != null) {
            return discovery.get(0).getAssetCode();
        }
        return "";
    }

    private List<TradingSignalViewDto> mergeCandidates(
            List<TradingSignalViewDto> scalp,
            List<TradingSignalViewDto> swing,
            List<TradingSignalViewDto> discovery,
            TradingSignalViewDto position) {
        List<TradingSignalViewDto> merged = new ArrayList<>();
        if (position != null) {
            merged.add(position);
        }
        merged.addAll(scalp);
        merged.addAll(swing);
        merged.addAll(discovery);

        Set<String> seen = new LinkedHashSet<>();
        return merged.stream()
                .filter(Objects::nonNull)
                .filter(row -> row.getAssetCode() != null && !row.getAssetCode().isBlank())
                .filter(row -> seen.add(row.getAssetCode()))
                .sorted(Comparator
                        .comparing(TradingSignalViewDto::getCombinedConfidence, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TradingSignalViewDto::getGeneratedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private Map<String, Object> buildStrategiesPayload(
            List<TradingSignalViewDto> scalp,
            List<TradingSignalViewDto> swing,
            List<TradingSignalViewDto> discovery,
            TradingSignalViewDto position) {
        Map<String, Object> strategies = new LinkedHashMap<>();
        strategies.put("SCALP", Map.of(
                "label", "단타",
                "purpose", "최근 뉴스 이벤트 대응",
                "items", scalp));
        strategies.put("SWING", Map.of(
                "label", "중기",
                "purpose", "주간 컨텍스트/중기 흐름",
                "items", swing));
        strategies.put("CHART_RESPONSE", Map.of(
                "label", "차트대응",
                "purpose", "압력/차트대응 확인",
                "items", position == null ? List.of() : List.of(position)));
        strategies.put("DISCOVERY", Map.of(
                "label", "6개월발굴",
                "purpose", "장기 발굴/테마 탐색",
                "items", discovery));
        return strategies;
    }

    private List<Map<String, Object>> buildWatchlistCards(List<TradingSignalViewDto> rows) {
        OffsetDateTime newsSince = OffsetDateTime.now().minusHours(24);
        return rows.stream()
                .limit(18)
                .map(row -> {
                    String assetCode = safeString(row.getAssetCode());
                    MarketQuoteSnapshotEntity quote = marketQuoteSnapshotRepository
                            .findTop1ByAssetCodeOrderBySnapshotUtcDesc(assetCode)
                            .orElse(null);
                    long newsCount24h = assetCode.isBlank() ? 0L
                            : newsAssetLinkRepository.countByAssetCodeAndCreatedAtAfter(assetCode, newsSince);

                    Map<String, Object> breakdown = parseJsonMap(row.getProbabilityReasonBreakdownJson());
                    String dataState = safeString(breakdown.get("data_state"));
                    BigDecimal uncertainty = decimal(breakdown.get("uncertainty_score"));

                    Map<String, Object> card = new LinkedHashMap<>();
                    card.put("signal_id", safeString(row.getSignalId()));
                    card.put("asset_code", assetCode);
                    card.put("asset_name", safeString(row.getAssetName()));
                    card.put("strategy_key", safeString(row.getStrategyKey()));
                    card.put("panel_purpose", safeString(row.getPanelPurpose()));
                    card.put("action", row.getAction() == null ? null : row.getAction().name());
                    card.put("recommendation_state", safeString(row.getRecommendationState()));
                    card.put("state_badge", safeString(row.getStateBadge()));
                    card.put("state_reason", safeString(row.getStateReason()));
                    card.put("combined_confidence", scale(row.getCombinedConfidence(), 4));
                    card.put("good_news_probability", scale(row.getGoodNewsProbability(), 4));
                    card.put("bad_news_probability", scale(row.getBadNewsProbability(), 4));
                    card.put("news_confidence", scale(row.getNewsConfidence(), 4));
                    card.put("uncertainty_score", scale(uncertainty, 4));
                    card.put("data_state", dataState);
                    card.put("quality_degraded", Boolean.TRUE.equals(row.getQualityDegraded()));
                    card.put("dedup_applied", Boolean.TRUE.equals(row.getDedupApplied()));
                    card.put("blocked_reason", safeString(row.getBlockedReason()));
                    card.put("theme_code", safeString(row.getThemeCode()));
                    card.put("selection_reason", safeString(row.getSelectionReason()));
                    card.put("generated_at", row.getGeneratedAt());
                    card.put("news_count_24h", newsCount24h);
                    appendQuoteFields(card, quote);
                    card.put("risk_badge", riskBadge(row, dataState, quote));
                    card.put("news_basis_text", buildNewsBasisText(row, dataState, newsCount24h));
                    card.put("recommendation_basis_text", buildRecommendationBasisText(row));
                    return card;
                })
                .toList();
    }

    private void appendQuoteFields(Map<String, Object> card, MarketQuoteSnapshotEntity quote) {
        if (quote == null) {
            card.put("last_price", null);
            card.put("change_pct", null);
            card.put("volume", null);
            card.put("quote_time_utc", null);
            card.put("quote_age_seconds", null);
            card.put("quote_provider", "");
            return;
        }
        OffsetDateTime quoteTime = quote.getQuoteTimeUtc() != null ? quote.getQuoteTimeUtc() : quote.getSnapshotUtc();
        Long ageSeconds = quoteTime == null ? null : ChronoUnit.SECONDS.between(quoteTime, OffsetDateTime.now());
        card.put("last_price", scale(quote.getLastPrice(), 6));
        card.put("change_pct", scale(quote.getChangePct(), 4));
        card.put("volume", scale(quote.getVolume(), 4));
        card.put("quote_time_utc", quoteTime);
        card.put("quote_age_seconds", ageSeconds == null ? null : Math.max(0L, ageSeconds));
        card.put("quote_provider", safeString(quote.getProviderName()));
    }

    private Map<String, Object> buildStatusBar(
            String country,
            String theme,
            List<TradingSignalViewDto> mergedSignals,
            List<Map<String, Object>> watchlist) {
        Map<String, Object> marketSummary = adminDiagnosticsService.getMarketCollectionSummary(country, null, 6);
        Map<String, Object> providerAudit = adminDiagnosticsService.getMarketCollectionProviderAudit(null, null, null, null, 20);
        Map<String, Object> signalConfidence = adminDiagnosticsService.getSignalConfidenceDistribution(country, 24);

        boolean signalGenerationEnabled = systemFeatureToggleService.isFeatureEnabled("SIGNAL_GENERATION", country, theme, null);
        boolean liveTradeToggleEnabled = systemFeatureToggleService.isFeatureEnabled("LIVE_TRADE", country, theme, null);
        boolean autoOrderToggleEnabled = systemFeatureToggleService.isFeatureEnabled("AUTO_ORDER_FULLY_AUTOMATED", country, theme, null);
        boolean liveTradeEffective = liveTradeEnabledProperty && liveTradeToggleEnabled;

        Map<String, Object> statusBar = new LinkedHashMap<>();
        statusBar.put("market_state", resolveMarketState(marketSummary, watchlist));
        statusBar.put("provider_status", buildProviderStatusSummary(marketSummary, providerAudit));
        statusBar.put("data_collection_status", buildCollectionStatusSummary(marketSummary));
        statusBar.put("auto_analysis_enabled", signalGenerationEnabled);
        statusBar.put("paper_trade_mode", !liveTradeEffective);
        statusBar.put("live_trade_enabled", liveTradeEffective);
        statusBar.put("mode_label", liveTradeEffective ? "실주문 가능(주의)" : "모의/참고용(PAPER_ONLY)");
        statusBar.put("auto_order_fully_automated_enabled", liveTradeEffective && autoOrderToggleEnabled);
        statusBar.put("signal_panel_status", buildSignalPanelStatus(mergedSignals));
        statusBar.put("rag_runtime", buildRagRuntimeSummary());
        statusBar.put("warnings", buildStatusWarnings(marketSummary, providerAudit, signalConfidence, watchlist, liveTradeEffective, autoOrderToggleEnabled));
        statusBar.put("updated_at", OffsetDateTime.now());
        return statusBar;
    }

    private Map<String, Object> buildProviderStatusSummary(Map<String, Object> marketSummary, Map<String, Object> providerAudit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success_rate", providerAudit.get("success_rate"));
        result.put("avg_latency_ms", providerAudit.get("avg_latency_ms"));
        result.put("failed_count", providerAudit.get("failed_count"));
        result.put("provider_health", marketSummary.get("provider_health"));
        result.put("latest_provider_job_time_utc", marketSummary.get("latest_provider_job_time_utc"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recentItems = providerAudit.get("items") instanceof List<?> items
                ? (List<Map<String, Object>>) (List<?>) items
                : List.of();
        result.put("recent", recentItems.stream().limit(5).toList());
        return result;
    }

    private Map<String, Object> buildCollectionStatusSummary(Map<String, Object> marketSummary) {
        Map<String, Object> result = new LinkedHashMap<>();
        long quoteSnapshots = longValue(marketSummary.get("quote_snapshot_count"));
        long priceBars = longValue(marketSummary.get("price_bar_count"));
        long failedJobs = longValue(marketSummary.get("provider_job_failed_count"));
        long emptyResponses = longValue(marketSummary.get("provider_job_empty_response_count"));
        result.put("quote_snapshot_count_6h", quoteSnapshots);
        result.put("price_bar_count_6h", priceBars);
        result.put("provider_job_failed_count_6h", failedJobs);
        result.put("provider_job_empty_response_count_6h", emptyResponses);
        result.put("unresolved_gap_count", longValue(marketSummary.get("unresolved_gap_count")));
        String level = "HEALTHY";
        if (quoteSnapshots <= 0 || priceBars <= 0) {
            level = "DATA_GAP";
        } else if (failedJobs > 0 || emptyResponses > 0) {
            level = "WARN";
        }
        result.put("status", level);
        return result;
    }

    private Map<String, Object> buildSignalPanelStatus(List<TradingSignalViewDto> mergedSignals) {
        long dataGap = mergedSignals.stream()
                .map(TradingSignalViewDto::getProbabilityReasonBreakdownJson)
                .map(this::parseJsonMap)
                .map(map -> safeString(map.get("data_state")))
                .filter(state -> "NO_MATCHED_NEWS".equals(state) || "INSUFFICIENT_DATA".equals(state))
                .count();
        long qualityWarn = mergedSignals.stream().filter(row -> Boolean.TRUE.equals(row.getQualityDegraded())).count();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("candidate_count", mergedSignals.size());
        status.put("data_gap_count", dataGap);
        status.put("quality_warning_count", qualityWarn);
        status.put("status", mergedSignals.isEmpty() ? "EMPTY" : (dataGap > 0 ? "PARTIAL_DATA_GAP" : "READY"));
        return status;
    }

    private Map<String, Object> buildRagRuntimeSummary() {
        OffsetDateTime since = OffsetDateTime.now().minusHours(24);
        long total = assistantRagAuditLogRepository.countByCreatedAtAfter(since);
        long failed = assistantRagAuditLogRepository.countBySuccessFalseAndCreatedAtAfter(since);
        List<AssistantRagAuditLogEntity> signalRows = assistantRagAuditLogRepository.findTop200ByRequestScopeOrderByCreatedAtDesc("SIGNAL_DETAIL");
        List<AssistantRagAuditLogEntity> traceRows = assistantRagAuditLogRepository.findTop200ByRequestScopeOrderByCreatedAtDesc("TRACE_DETAIL");
        List<AssistantRagAuditLogEntity> recent = new ArrayList<>();
        recent.addAll(signalRows);
        recent.addAll(traceRows);
        recent = recent.stream()
                .filter(row -> row.getCreatedAt() != null && row.getCreatedAt().isAfter(since))
                .sorted(Comparator.comparing(AssistantRagAuditLogEntity::getCreatedAt).reversed())
                .limit(200)
                .toList();

        long fallbackCount = recent.stream().filter(row -> Boolean.TRUE.equals(row.getFallbackApplied())).count();
        long timeoutCount = recent.stream().filter(row -> safeString(row.getErrorCode()).toUpperCase(Locale.ROOT).contains("TIMEOUT")).count();
        BigDecimal avgLatency = recent.stream()
                .map(AssistantRagAuditLogEntity::getLatencyMsTotal)
                .filter(Objects::nonNull)
                .map(BigDecimal::valueOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!recent.isEmpty()) {
            avgLatency = avgLatency.divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_UP);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window_hours", 24);
        result.put("total_count", total);
        result.put("failed_count", failed);
        result.put("fallback_count", fallbackCount);
        result.put("timeout_count", timeoutCount);
        result.put("success_rate", total <= 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(total - failed).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP));
        result.put("avg_latency_ms", avgLatency.setScale(2, RoundingMode.HALF_UP));
        return result;
    }

    private List<String> buildStatusWarnings(
            Map<String, Object> marketSummary,
            Map<String, Object> providerAudit,
            Map<String, Object> signalConfidence,
            List<Map<String, Object>> watchlist,
            boolean liveTradeEffective,
            boolean autoOrderToggleEnabled) {
        List<String> warnings = new ArrayList<>();
        if (longValue(marketSummary.get("unresolved_gap_count")) > 0) {
            warnings.add("시장데이터 갭 미해결 건이 존재합니다.");
        }
        if (longValue(providerAudit.get("failed_count")) > 0) {
            warnings.add("최근 Provider 호출 실패가 발생했습니다.");
        }
        if (decimal(signalConfidence.get("avg_combined_confidence")).compareTo(BigDecimal.valueOf(0.45d)) < 0) {
            warnings.add("최근 시그널 평균 결합신뢰도가 낮습니다.");
        }
        long dataGapCards = watchlist.stream()
                .map(row -> safeString(row.get("data_state")))
                .filter(state -> "NO_MATCHED_NEWS".equals(state) || "INSUFFICIENT_DATA".equals(state))
                .count();
        if (dataGapCards > 0) {
            warnings.add("일부 종목은 뉴스 매핑/표본 부족으로 확률이 보류 상태입니다.");
        }
        if (autoOrderToggleEnabled && !liveTradeEffective) {
            warnings.add("AUTO_ORDER_FULLY_AUTOMATED 토글이 켜져도 실주문 모드가 비활성이라 주문은 실행되지 않습니다.");
        }
        if (liveTradeEffective) {
            warnings.add("실주문 가능 상태입니다. 승인 파이프라인 없이 자동 실행 금지 정책을 재확인하세요.");
        }
        return warnings;
    }

    private String resolveMarketState(Map<String, Object> marketSummary, List<Map<String, Object>> watchlist) {
        BigDecimal avgScore = decimal(marketSummary.get("avg_quality_score"));
        long quoteCount = longValue(marketSummary.get("quote_snapshot_count"));
        long gapCount = longValue(marketSummary.get("unresolved_gap_count"));
        boolean staleQuote = watchlist.stream()
                .map(row -> row.get("quote_age_seconds"))
                .filter(Objects::nonNull)
                .map(this::longValue)
                .anyMatch(seconds -> seconds > 600L);
        if (quoteCount <= 0 || watchlist.isEmpty()) {
            return "DATA_GAP";
        }
        if (gapCount > 0 || staleQuote || avgScore.compareTo(BigDecimal.valueOf(70d)) < 0) {
            return "WARN";
        }
        return "ACTIVE";
    }

    private String riskBadge(TradingSignalViewDto row, String dataState, MarketQuoteSnapshotEntity quote) {
        if ("NO_MATCHED_NEWS".equals(dataState) || "INSUFFICIENT_DATA".equals(dataState)) {
            return "데이터부족";
        }
        if (Boolean.TRUE.equals(row.getQualityDegraded())) {
            return "품질주의";
        }
        if (row.getBlockedReason() != null && !row.getBlockedReason().isBlank()) {
            return "차단";
        }
        if (quote == null) {
            return "시세미수신";
        }
        Long quoteAge = quote.getQuoteTimeUtc() == null && quote.getSnapshotUtc() == null
                ? null
                : ChronoUnit.SECONDS.between(
                        quote.getQuoteTimeUtc() == null ? quote.getSnapshotUtc() : quote.getQuoteTimeUtc(),
                        OffsetDateTime.now());
        if (quoteAge != null && quoteAge > 600L) {
            return "시세지연";
        }
        return "정상";
    }

    private String buildNewsBasisText(TradingSignalViewDto row, String dataState, long newsCount24h) {
        if ("NO_MATCHED_NEWS".equals(dataState) || "INSUFFICIENT_DATA".equals(dataState)) {
            return "뉴스 매핑/표본 부족으로 보류";
        }
        return "24h 뉴스 " + newsCount24h + "건 · "
                + "호재 " + scale(row.getGoodNewsProbability(), 3)
                + " / 악재 " + scale(row.getBadNewsProbability(), 3);
    }

    private String buildRecommendationBasisText(TradingSignalViewDto row) {
        StringBuilder sb = new StringBuilder();
        if (row.getStrategyKey() != null && !row.getStrategyKey().isBlank()) {
            sb.append(row.getStrategyKey());
        }
        if (row.getPanelPurpose() != null && !row.getPanelPurpose().isBlank()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(row.getPanelPurpose());
        }
        if (row.getStateReason() != null && !row.getStateReason().isBlank()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(row.getStateReason());
        }
        return sb.length() == 0 ? "추천 근거 정보 없음" : sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(rawJson, new TypeReference<>() {});
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonMapList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = objectMapper.readValue(rawJson, new TypeReference<>() {});
            if (!(parsed instanceof List<?> list)) {
                return List.of();
            }
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(row -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        row.forEach((key, value) -> result.put(String.valueOf(key), value));
                        return result;
                    })
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String classifyQuestionType(String question) {
        String q = safeString(question).toLowerCase(Locale.ROOT);
        if (containsAny(q, "왜", "이유", "근거", "설명")) {
            return "WHY";
        }
        if (containsAny(q, "리스크", "위험", "몰빵", "비중", "잠금", "lock")) {
            return "RISK";
        }
        if (containsAny(q, "뉴스", "호재", "악재", "불확실")) {
            return "NEWS";
        }
        if (containsAny(q, "차트", "압력", "거래량", "평단", "추매")) {
            return "CHART";
        }
        if (containsAny(q, "익절", "손절", "매도", "sell")) {
            return "SELL_PLAN";
        }
        if (containsAny(q, "매수", "buy", "진입")) {
            return "BUY_PLAN";
        }
        if (containsAny(q, "언제", "조건", "바뀌", "변경")) {
            return "CHANGE_TRIGGER";
        }
        return "GENERAL";
    }

    private String buildQaAnswerText(
            String questionType,
            String question,
            SignalDetailDto detail,
            Map<String, Object> breakdown,
            Map<String, Object> riskGuide,
            List<String> newsEvidence,
            List<String> chartEvidence,
            List<String> volumeEvidence,
            List<String> riskEvidence,
            List<String> missing,
            List<String> changes,
            String ragSummary,
            List<String> ragEvidence,
            List<String> ragCautions) {
        String action = detail.getAction() == null ? "WATCH" : detail.getAction().name();
        String detailDataState = safeString(detail.getDataState());
        String dataState = safeString(detailDataState.isBlank() ? breakdown.get("data_state") : detailDataState);
        BigDecimal good = scale(detail.getGoodNewsProbability(), 3);
        BigDecimal bad = scale(detail.getBadNewsProbability(), 3);
        BigDecimal confidence = scale(detail.getCombinedConfidence(), 3);
        String blocked = safeString(detail.getBlockedReason());
        boolean buyLock = Boolean.TRUE.equals(riskGuide.get("buy_lock_active"));
        String tp = safeString(riskGuide.get("take_profit_pct"));
        String sl = safeString(riskGuide.get("stop_loss_pct"));
        String avgDownAllowed = Boolean.TRUE.equals(riskGuide.get("avg_down_allowed")) ? "허용" : "보류";

        List<String> lines = new ArrayList<>();
        lines.add("현재 규칙 엔진 결정은 " + action + "이며, 이 Q&A는 결정을 변경하지 않고 근거를 정리합니다.");
        if (!blocked.isBlank()) {
            lines.add("전략/리스크 차단 사유가 감지되어 있습니다: " + blocked);
        }
        if (!dataState.isBlank()) {
            lines.add("데이터 상태는 " + dataState + " 입니다.");
        }

        switch (safeString(questionType)) {
            case "WHY" -> {
                lines.add("결합신뢰 " + confidence + " / 호재 " + good + " / 악재 " + bad + " 기준으로 규칙 엔진이 판단했습니다.");
                appendTopLine(lines, "판단 사유", safeString(detail.getDecisionWhy()));
                appendEvidenceLines(lines, "뉴스 근거", newsEvidence, 2);
                appendEvidenceLines(lines, "차트/압력 근거", mergeLists(chartEvidence, volumeEvidence), 2);
                appendEvidenceLines(lines, "리스크 근거", riskEvidence, 2);
            }
            case "NEWS" -> {
                lines.add("뉴스 해석은 단정이 아니라 확률/근거 분리로 표시됩니다.");
                appendEvidenceLines(lines, "뉴스 근거", newsEvidence, 3);
                if (!ragSummary.isBlank()) {
                    appendTopLine(lines, "RAG 보조요약", ragSummary);
                }
                appendEvidenceLines(lines, "RAG 근거요약", ragEvidence, 2);
                appendEvidenceLines(lines, "RAG 주의점", ragCautions, 2);
            }
            case "CHART" -> {
                appendEvidenceLines(lines, "차트/압력 근거", chartEvidence, 3);
                appendEvidenceLines(lines, "거래량/압력 보조근거", volumeEvidence, 2);
                lines.add("평단가 대응(추매형)은 현재 " + avgDownAllowed + " 상태이며, BUY_LOCK/차단 사유를 우선 확인해야 합니다.");
            }
            case "RISK" -> {
                appendEvidenceLines(lines, "리스크 근거", riskEvidence, 3);
                lines.add("BUY_LOCK 상태: " + (buyLock ? "활성(재분석 전 매수 금지)" : "비활성"));
                if (!tp.isBlank() || !sl.isBlank()) {
                    lines.add("참고 정책값: 익절 " + blankIfEmpty(tp, "-") + "% / 손절 " + blankIfEmpty(sl, "-") + "%");
                }
                if (!blocked.isBlank()) {
                    lines.add("차단 사유 해소 전에는 액션 변경을 가정하지 마세요.");
                }
            }
            case "BUY_PLAN" -> {
                lines.add("매수 관련 질문이지만 최종 결정은 규칙 엔진/리스크 정책이 우선입니다.");
                lines.add("현재 규칙 엔진 액션: " + action + (buyLock ? " (BUY_LOCK 활성)" : ""));
                appendTopLine(lines, "분할매수 가이드", safeString(riskGuide.get("buy_split_ratios")));
                appendTopLine(lines, "평단가 대응", "현재 " + avgDownAllowed + " / 단계 " + safeString(riskGuide.get("avg_down_stage")));
                appendEvidenceLines(lines, "매수 전 확인 조건", missing, 2);
            }
            case "SELL_PLAN" -> {
                lines.add("매도/익절/손절 질문은 정책 참고값 기준으로만 요약합니다 (실주문 신호 아님).");
                if (!tp.isBlank() || !sl.isBlank()) {
                    lines.add("정책 참고값: 익절 " + blankIfEmpty(tp, "-") + "% / 손절 " + blankIfEmpty(sl, "-") + "%");
                }
                appendTopLine(lines, "분할매도 가이드", safeString(riskGuide.get("sell_split_ratios")));
                appendEvidenceLines(lines, "리스크 근거", riskEvidence, 2);
            }
            case "CHANGE_TRIGGER" -> {
                appendEvidenceLines(lines, "변경 조건", changes, 3);
                appendEvidenceLines(lines, "부족 요건", missing, 3);
            }
            default -> {
                appendTopLine(lines, "질문 요약", safeString(question));
                lines.add("현재 액션 " + action + " / 결합신뢰 " + confidence + " / 데이터상태 " + blankIfEmpty(dataState, "-"));
                appendTopLine(lines, "핵심 사유", safeString(detail.getDecisionWhy()));
                appendEvidenceLines(lines, "주요 근거", mergeLists(newsEvidence, chartEvidence, riskEvidence), 3);
                appendEvidenceLines(lines, "변경 조건", changes, 2);
            }
        }

        if (lines.stream().noneMatch(line -> line.contains("RAG 보조요약")) && !ragSummary.isBlank()) {
            lines.add("RAG 보조요약(참고): " + ragSummary);
        }
        return String.join(" ", lines.stream().filter(v -> v != null && !v.isBlank()).limit(10).toList());
    }

    private void appendTopLine(List<String> lines, String label, String value) {
        if (lines == null || value == null || value.isBlank()) {
            return;
        }
        lines.add(label + ": " + value);
    }

    private void appendEvidenceLines(List<String> lines, String label, List<String> values, int limit) {
        if (lines == null || values == null || values.isEmpty()) {
            return;
        }
        List<String> filtered = values.stream()
                .filter(v -> v != null && !v.isBlank())
                .limit(Math.max(1, limit))
                .toList();
        if (filtered.isEmpty()) {
            return;
        }
        lines.add(label + ": " + String.join(" / ", filtered));
    }

    @SafeVarargs
    private List<String> mergeLists(List<String>... parts) {
        List<String> merged = new ArrayList<>();
        if (parts == null) {
            return merged;
        }
        for (List<String> part : parts) {
            if (part == null) {
                continue;
            }
            for (String row : part) {
                if (row != null && !row.isBlank()) {
                    merged.add(row);
                }
            }
        }
        return merged;
    }

    private List<String> limitLines(List<String> values, int limit) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .limit(Math.max(1, limit))
                .toList();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream()
                .map(this::safeString)
                .filter(v -> !v.isBlank())
                .toList();
    }

    private boolean containsAny(String text, String... tokens) {
        if (text == null || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isBlank() && text.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String blankIfEmpty(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<String> suggestedQuestions(SignalDetailDto detail) {
        String asset = safeString(detail == null ? null : detail.getAssetName());
        if (asset.isBlank()) {
            asset = safeString(detail == null ? null : detail.getAssetCode());
        }
        String prefix = asset.isBlank() ? "이 종목" : asset;
        return List.of(
                prefix + " 왜 " + (detail != null && detail.getAction() != null ? detail.getAction().name() : "WATCH") + " 인가?",
                prefix + " 뉴스 근거와 불확실성은?",
                prefix + " 차트/압력 기준 리스크는?",
                prefix + " 매수/매도 전에 바뀌어야 할 조건은?");
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        return safe.setScale(scale, RoundingMode.HALF_UP);
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private String safeString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record StrategyRows(
            List<TradingSignalViewDto> scalp,
            List<TradingSignalViewDto> swing,
            List<TradingSignalViewDto> discovery,
            TradingSignalViewDto position,
            Map<String, Object> summary) {
    }
}

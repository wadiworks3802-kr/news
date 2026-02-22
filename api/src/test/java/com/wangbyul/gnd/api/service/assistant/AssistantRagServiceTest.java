package com.wangbyul.gnd.api.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.AssistantRagProperties;
import com.wangbyul.gnd.api.dto.AssistantRagInsightDto;
import com.wangbyul.gnd.api.service.SystemFeatureToggleService;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.repository.AssistantRagAuditLogRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 경량 RAG 보조 계층 테스트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@ExtendWith(MockitoExtension.class)
class AssistantRagServiceTest {

    @Mock
    private AssistantRagContextBuilderService contextBuilderService;
    @Mock
    private AssistantRagModelClient modelClient;
    @Mock
    private AssistantRagAuditLogRepository assistantRagAuditLogRepository;
    @Mock
    private SystemFeatureToggleService systemFeatureToggleService;

    private AssistantRagProperties properties;
    private AssistantRagService assistantRagService;

    @BeforeEach
    void setUp() {
        properties = new AssistantRagProperties();
        properties.setTimeoutMillis(80L);
        properties.setLatencyWarningMillis(20L);
        properties.setCircuitWindowSize(5);
        properties.setCircuitConsecutiveFailureThreshold(2);
        properties.setDataGapStates(List.of("NO_MATCHED_NEWS", "INSUFFICIENT_DATA"));
        assistantRagService = new AssistantRagService(
                contextBuilderService,
                modelClient,
                assistantRagAuditLogRepository,
                systemFeatureToggleService,
                properties,
                new ObjectMapper());
        when(assistantRagAuditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void signalDetailShouldReturnStructuredSummaryAndKeepRuleEngineAction() {
        TradingSignalEntity signal = signal("sig-1", "AAA1", SignalActionType.WATCH);
        AssetUniverseEntity asset = asset("AAA1", "Alpha");
        when(contextBuilderService.buildSignalDetailContext(eq(signal), eq(asset), any(), any()))
                .thenReturn(signalContext("sig-1", "AAA1", "Alpha", "WATCH", ""));
        when(systemFeatureToggleService.isFeatureEnabled("RAG_ASSISTANT", "KR", "AI", "AAA1")).thenReturn(true);
        when(modelClient.generateSignalDetailSummary(any())).thenReturn(
                new AssistantRagModelClient.SignalDetailModelOutput(
                        "요약 문장",
                        List.of("근거1"),
                        List.of("주의1"),
                        List.of(),
                        List.of("조건1"),
                        "설명문",
                        "WATCH",
                        true,
                        Map.of("k", "v")));

        AssistantRagService.SignalDetailAssistResult result = assistantRagService.assistSignalDetail(
                signal, asset, List.of("CHECK1"), Map.of(), true);

        AssistantRagInsightDto insight = result.insight();
        assertThat(insight).isNotNull();
        assertThat(insight.getApplied()).isTrue();
        assertThat(insight.getFallbackApplied()).isFalse();
        assertThat(insight.getSource()).isEqualTo("RAG_MODEL");
        assertThat(insight.getRuleEngineAction()).isEqualTo("WATCH");
        assertThat(insight.getRuleEngineActionLocked()).isTrue();
        assertThat(result.ragContextRefsJson()).contains("signal");
        verify(assistantRagAuditLogRepository).save(any());
    }

    @Test
    void signalDetailShouldFallbackOnTimeout() {
        TradingSignalEntity signal = signal("sig-2", "BBB1", SignalActionType.BUY_CANDIDATE);
        AssetUniverseEntity asset = asset("BBB1", "Beta");
        when(contextBuilderService.buildSignalDetailContext(eq(signal), eq(asset), any(), any()))
                .thenReturn(signalContext("sig-2", "BBB1", "Beta", "BUY_CANDIDATE", ""));
        when(systemFeatureToggleService.isFeatureEnabled("RAG_ASSISTANT", "KR", "AI", "BBB1")).thenReturn(true);
        when(modelClient.generateSignalDetailSummary(any())).thenAnswer(inv -> {
            Thread.sleep(150L);
            return new AssistantRagModelClient.SignalDetailModelOutput(
                    "늦은 요약", List.of("근거"), List.of("주의"), List.of(), List.of(), "설명", "BUY_CANDIDATE", true, Map.of());
        });

        AssistantRagService.SignalDetailAssistResult result = assistantRagService.assistSignalDetail(
                signal, asset, List.of(), Map.of(), true);

        assertThat(result.insight().getFallbackApplied()).isTrue();
        assertThat(result.insight().getSource()).isEqualTo("RULE_FALLBACK");
        assertThat(result.insight().getErrorCode()).isEqualTo("TIMEOUT");
        assertThat(result.insight().getRuleEngineAction()).isEqualTo("BUY_CANDIDATE");
    }

    @Test
    void signalDetailShouldBeDisabledWhenFeatureToggleOff() {
        TradingSignalEntity signal = signal("sig-3", "CCC1", SignalActionType.HOLD);
        AssetUniverseEntity asset = asset("CCC1", "Gamma");
        when(contextBuilderService.buildSignalDetailContext(eq(signal), eq(asset), any(), any()))
                .thenReturn(signalContext("sig-3", "CCC1", "Gamma", "HOLD", ""));
        when(systemFeatureToggleService.isFeatureEnabled("RAG_ASSISTANT", "KR", "AI", "CCC1")).thenReturn(false);

        AssistantRagService.SignalDetailAssistResult result = assistantRagService.assistSignalDetail(
                signal, asset, List.of(), Map.of(), true);

        assertThat(result.insight().getEnabled()).isFalse();
        assertThat(result.insight().getApplied()).isFalse();
        assertThat(result.insight().getSource()).isEqualTo("DISABLED");
        assertThat(result.insight().getErrorCode()).isEqualTo("RAG_ASSISTANT_OFF");
    }

    @Test
    void signalDetailShouldFallbackWhenModelOutputViolatesRuleEngineActionLock() {
        TradingSignalEntity signal = signal("sig-4", "DDD1", SignalActionType.WATCH);
        AssetUniverseEntity asset = asset("DDD1", "Delta");
        when(contextBuilderService.buildSignalDetailContext(eq(signal), eq(asset), any(), any()))
                .thenReturn(signalContext("sig-4", "DDD1", "Delta", "WATCH", "INSUFFICIENT_DATA"));
        when(systemFeatureToggleService.isFeatureEnabled("RAG_ASSISTANT", "KR", "AI", "DDD1")).thenReturn(true);
        when(modelClient.generateSignalDetailSummary(any())).thenReturn(
                new AssistantRagModelClient.SignalDetailModelOutput(
                        "과도한 요약",
                        List.of("근거1"),
                        List.of("데이터 부족 주의"),
                        List.of(),
                        List.of(),
                        "설명",
                        "BUY_CANDIDATE",
                        true,
                        Map.of()));

        AssistantRagService.SignalDetailAssistResult result = assistantRagService.assistSignalDetail(
                signal, asset, List.of(), Map.of("data_state", "INSUFFICIENT_DATA"), true);

        assertThat(result.insight().getFallbackApplied()).isTrue();
        assertThat(result.insight().getErrorCode()).isEqualTo("RULE_ENGINE_ACTION_MISMATCH");
        assertThat(result.insight().getRuleEngineAction()).isEqualTo("WATCH");
    }

    @Test
    void traceDetailShouldReturnHumanFriendlySummary() {
        when(contextBuilderService.buildTraceDetailContext(eq("trace-1"), any())).thenReturn(traceContext("trace-1"));
        when(systemFeatureToggleService.isFeatureEnabled("RAG_ASSISTANT", null, null, null)).thenReturn(true);
        when(modelClient.generateTraceDetailSummary(any())).thenReturn(
                new AssistantRagModelClient.TraceDetailModelOutput(
                        "trace 요약",
                        List.of("전략 실행 2건"),
                        List.of("주의 없음"),
                        "설명문",
                        Map.of()));

        Map<String, Object> result = assistantRagService.assistTraceDetail("trace-1", Map.of("counts", Map.of()), true);

        assertThat(result.get("applied")).isEqualTo(true);
        assertThat(result.get("fallback_applied")).isEqualTo(false);
        assertThat(String.valueOf(result.get("summary"))).contains("trace");
    }

    private TradingSignalEntity signal(String id, String assetCode, SignalActionType action) {
        TradingSignalEntity entity = new TradingSignalEntity();
        entity.setId(id);
        entity.setAssetCode(assetCode);
        entity.setCountry("KR");
        entity.setTheme("AI");
        entity.setAction(action);
        entity.setTraceId("trace-" + id);
        entity.setGoodNewsProbability(new BigDecimal("0.55"));
        entity.setBadNewsProbability(new BigDecimal("0.21"));
        entity.setCombinedConfidence(new BigDecimal("0.62"));
        entity.setGeneratedAt(OffsetDateTime.now());
        return entity;
    }

    private AssetUniverseEntity asset(String code, String name) {
        AssetUniverseEntity entity = new AssetUniverseEntity();
        entity.setAssetCode(code);
        entity.setAssetName(name);
        entity.setCountry("KR");
        entity.setTheme("AI");
        return entity;
    }

    private AssistantRagContextBuilderService.SignalDetailRagContext signalContext(
            String signalId,
            String assetCode,
            String assetName,
            String action,
            String dataState) {
        Map<String, Object> signalSummary = new java.util.LinkedHashMap<>();
        signalSummary.put("signal_id", signalId);
        signalSummary.put("asset_code", assetCode);
        signalSummary.put("asset_name", assetName);
        signalSummary.put("country", "KR");
        signalSummary.put("theme", "AI");
        signalSummary.put("action", action);
        signalSummary.put("blocked", false);
        signalSummary.put("data_state", dataState);
        signalSummary.put("good_news_probability", new BigDecimal("0.51"));
        signalSummary.put("bad_news_probability", new BigDecimal("0.22"));
        signalSummary.put("combined_confidence", new BigDecimal("0.64"));

        return new AssistantRagContextBuilderService.SignalDetailRagContext(
                signalId,
                assetCode,
                assetName,
                "KR",
                "AI",
                "trace-" + signalId,
                signalSummary,
                List.of("CHECK1"),
                List.of(Map.of("news_id", "N1", "event_type", "ORDER", "impact_direction", "POSITIVE")),
                Map.of("provider", "mock", "change_pct", new BigDecimal("0.011"), "stale", false),
                Map.of("timeframe", "1m", "bar_count", 10, "window_return_pct", new BigDecimal("0.005"), "insufficient_bars", false),
                List.of(Map.of("id", 1L, "engine_type", "FUSION")),
                List.of(Map.of("ref_type", "signal", "signal_id", signalId)),
                "[{\"ref_type\":\"signal\",\"signal_id\":\"" + signalId + "\"}]");
    }

    private AssistantRagContextBuilderService.TraceDetailRagContext traceContext(String traceId) {
        return new AssistantRagContextBuilderService.TraceDetailRagContext(
                traceId,
                Map.of("signal_audits", 2, "provider_audits", 3, "gap_events", 0, "strategy_runs", 2),
                Map.of("provider_audits", 3, "gap_events", 0, "failed_provider_audits", 0),
                Map.of("strategy_runs", 2, "signal_audits", 2, "blocked_signal_audits", 0),
                Map.of("feature_toggle_count", 1),
                List.of(Map.of("ref_type", "trace", "trace_id", traceId)),
                "[{\"ref_type\":\"trace\",\"trace_id\":\"" + traceId + "\"}]");
    }
}

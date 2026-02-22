package com.wangbyul.gnd.api.service.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.dto.BuyLockStatusDto;
import com.wangbyul.gnd.api.dto.PaperTradeRiskDto;
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
        data.put("watchlist", watchlist);
        data.put("risk_panel", riskPanel);
        data.put("locks", locks);
        data.put("selected_signal_id", selectedSignalId);
        data.put("selected_asset_code", selectedAssetCode);
        data.put("generated_at", OffsetDateTime.now());
        return data;
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
}

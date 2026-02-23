package com.wangbyul.gnd.api.controller;

import com.wangbyul.gnd.api.dto.BacktestComparisonDto;
import com.wangbyul.gnd.api.dto.BacktestReportDto;
import com.wangbyul.gnd.api.dto.InsightDto;
import com.wangbyul.gnd.api.dto.PressureAnalysisDto;
import com.wangbyul.gnd.api.dto.SignalDetailDto;
import com.wangbyul.gnd.api.dto.StockSignalDto;
import com.wangbyul.gnd.api.dto.TradingSignalViewDto;
import com.wangbyul.gnd.api.dto.WeeklyContextDto;
import com.wangbyul.gnd.api.service.StockSignalService;
import com.wangbyul.gnd.api.service.assistant.AssistantDashboardService;
import com.wangbyul.gnd.api.service.signal.BacktestComparisonService;
import com.wangbyul.gnd.api.service.signal.TradingSignalEngineService;
import com.wangbyul.gnd.core.dto.ApiEnvelope;
import com.wangbyul.gnd.core.repository.InsightLogRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.NewsRepository;
import com.wangbyul.gnd.core.market.provider.MarketDataProviderRouter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
/**
 * InsightController 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Validated
@RestController
@RequestMapping("/api")
public class InsightController {

    private final InsightLogRepository insightLogRepository;
    private final NewsRepository newsRepository;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final MarketDataProviderRouter marketDataProviderRouter;
    private final StockSignalService stockSignalService;
    private final TradingSignalEngineService tradingSignalEngineService;
    private final BacktestComparisonService backtestComparisonService;
    private final AssistantDashboardService assistantDashboardService;

    @Value("${app.universe.quote-freshness-threshold-minutes:180}")
    private long quoteFreshnessThresholdMinutes;

    public InsightController(
            InsightLogRepository insightLogRepository,
            NewsRepository newsRepository,
            MarketQuoteSnapshotRepository marketQuoteSnapshotRepository,
            MarketDataProviderRouter marketDataProviderRouter,
            StockSignalService stockSignalService,
            TradingSignalEngineService tradingSignalEngineService,
            BacktestComparisonService backtestComparisonService,
            AssistantDashboardService assistantDashboardService) {
        this.insightLogRepository = insightLogRepository;
        this.newsRepository = newsRepository;
        this.marketQuoteSnapshotRepository = marketQuoteSnapshotRepository;
        this.marketDataProviderRouter = marketDataProviderRouter;
        this.stockSignalService = stockSignalService;
        this.tradingSignalEngineService = tradingSignalEngineService;
        this.backtestComparisonService = backtestComparisonService;
        this.assistantDashboardService = assistantDashboardService;
    }

    @GetMapping("/insight")
    public ApiEnvelope<List<InsightDto>> getInsight(
            @RequestParam @NotBlank @Pattern(regexp = "^(BRK|POL|ECO|MKT|DEV|IND)$") String category,
            @RequestParam(defaultValue = "24h") String period,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{2,5}$") String country) {

        if (!recGateSatisfied(country, period)) {
            return ApiEnvelope.<List<InsightDto>>builder()
                    .data(List.of())
                    .meta(Map.of("recommendation_generated", false, "reason", "REC_GATE_BLOCKED"))
                    .traceId(traceId())
                    .build();
        }

        List<InsightDto> data = insightLogRepository.findTop50ByCategoryOrderByGeneratedAtDesc(category)
                .stream()
                .map(log -> InsightDto.builder()
                        .category(log.getCategory())
                        .content(log.getContent())
                        .riskFlag(Boolean.TRUE.equals(log.getRiskFlag()))
                        .traceId(log.getTraceId())
                        .build())
                .toList();

        return ApiEnvelope.<List<InsightDto>>builder()
                .data(data)
                .meta(Map.of("period", period, "recommendation_generated", true))
                .traceId(traceId())
                .build();
    }

    private boolean recGateSatisfied(String country, String period) {
        OffsetDateTime since = switch (period) {
            case "7d" -> OffsetDateTime.now().minusDays(7);
            case "30d" -> OffsetDateTime.now().minusDays(30);
            default -> OffsetDateTime.now().minusHours(24);
        };

        var candidates = newsRepository.findTop200ByCountryOrderByPubUtcDesc(country).stream()
                .filter(news -> news.getPubUtc() != null && news.getPubUtc().isAfter(since))
                .toList();

        long sourceCount = candidates.stream().map(news -> news.getSource().getSid()).distinct().count();
        long evidenceCount = candidates.stream()
                .filter(news -> news.getEvidenceSpans() != null && !news.getEvidenceSpans().isBlank())
                .count();
        BigDecimal avgTrust = candidates.isEmpty()
                ? BigDecimal.ZERO
                : candidates.stream()
                        .map(news -> news.getTrustScore() == null ? BigDecimal.ZERO : news.getTrustScore())
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(candidates.size()), java.math.RoundingMode.HALF_UP);

        return sourceCount >= 2 && evidenceCount > 0 && avgTrust.compareTo(BigDecimal.valueOf(0.3)) >= 0;
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return trace == null ? "" : trace;
    }

    private Map<String, Object> metaWithStandardFields(Map<String, Object> base) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (base != null) {
            meta.putAll(base);
        }
        meta.put("trace_id", traceId());
        meta.putIfAbsent("provider_name", "");
        meta.putIfAbsent("is_delayed", false);
        meta.putIfAbsent("warnings", List.of());
        return meta;
    }

    private Map<String, Object> metaWithMarketDataContext(Map<String, Object> base, List<String> assetCodes) {
        Map<String, Object> meta = metaWithStandardFields(base);
        List<String> distinctAssetCodes = assetCodes == null
                ? List.of()
                : assetCodes.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(code -> !code.isBlank())
                        .distinct()
                        .limit(30)
                        .toList();

        List<String> providerNames = new ArrayList<>();
        int delayedAssetCount = 0;
        int missingQuoteCount = 0;
        for (String assetCode : distinctAssetCodes) {
            var quote = marketQuoteSnapshotRepository.findTop1ByAssetCodeOrderBySnapshotUtcDesc(assetCode).orElse(null);
            if (quote == null) {
                missingQuoteCount++;
                continue;
            }
            String provider = normalizeProviderName(quote.getProviderName());
            providerNames.add(provider);
            OffsetDateTime baseTime = quote.getSnapshotUtc() != null ? quote.getSnapshotUtc() : quote.getCreatedAt();
            if (baseTime == null
                    || baseTime.isBefore(OffsetDateTime.now().minusMinutes(Math.max(1L, quoteFreshnessThresholdMinutes)))) {
                delayedAssetCount++;
            }
        }

        LinkedHashSet<String> providerSet = new LinkedHashSet<>(providerNames);
        String providerName = providerSet.isEmpty()
                ? normalizeProviderName(marketDataProviderRouter.activeProviderId())
                : (providerSet.size() == 1 ? providerSet.iterator().next() : (providerSet.contains("MOCK") ? "MIXED_WITH_MOCK" : "MIXED"));
        boolean isDelayed = delayedAssetCount > 0 || (missingQuoteCount > 0 && !distinctAssetCodes.isEmpty());
        boolean mockDetected = containsMockProvider(providerName) || providerSet.stream().anyMatch(this::containsMockProvider);

        List<String> warnings = new ArrayList<>();
        if (mockDetected) {
            warnings.add("시장데이터 provider_name=MOCK 입니다. 실거래 판단용 실데이터가 아닙니다.");
        }
        if (isDelayed) {
            warnings.add("일부 종목의 시세가 지연되었거나 수집되지 않았습니다.");
        }
        if (missingQuoteCount > 0) {
            warnings.add("시세 미수신 종목 " + missingQuoteCount + "건");
        }

        meta.put("provider_name", providerName);
        meta.put("provider_names", providerSet.stream().toList());
        meta.put("is_delayed", isDelayed);
        meta.put("warnings", warnings);
        meta.put("mock_provider_warning", mockDetected);
        if (!distinctAssetCodes.isEmpty()) {
            meta.put("asset_count_evaluated", distinctAssetCodes.size());
            meta.put("delayed_asset_count", delayedAssetCount);
            meta.put("missing_quote_asset_count", missingQuoteCount);
        }
        return meta;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metaWithAssistantDashboardContext(Map<String, Object> base, Map<String, Object> dashboard) {
        Map<String, Object> meta = metaWithStandardFields(base);
        List<String> assetCodes = new ArrayList<>();
        LinkedHashSet<String> providers = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        boolean delayed = false;
        if (dashboard != null) {
            Object watchlistObj = dashboard.get("watchlist");
            if (watchlistObj instanceof List<?> watchlistRows) {
                for (Object rowObj : watchlistRows) {
                    if (!(rowObj instanceof Map<?, ?> row)) {
                        continue;
                    }
                    Object code = row.get("asset_code");
                    if (code instanceof String codeStr && !codeStr.isBlank()) {
                        assetCodes.add(codeStr.trim());
                    }
                    Object provider = row.get("quote_provider");
                    if (provider instanceof String providerStr && !providerStr.isBlank()) {
                        providers.add(normalizeProviderName(providerStr));
                    }
                    Object age = row.get("quote_age_seconds");
                    if (age instanceof Number n && n.longValue() > Math.max(60L, quoteFreshnessThresholdMinutes * 60L)) {
                        delayed = true;
                    }
                }
            }
            Object statusBarObj = dashboard.get("status_bar");
            if (statusBarObj instanceof Map<?, ?> statusBar) {
                Object warningObj = statusBar.get("warnings");
                if (warningObj instanceof List<?> warningRows) {
                    for (Object item : warningRows) {
                        if (item instanceof String s && !s.isBlank()) {
                            warnings.add(s);
                            if (s.contains("지연") || s.contains("갭")) {
                                delayed = true;
                            }
                        }
                    }
                }
            }
        }
        Map<String, Object> enriched = metaWithMarketDataContext(meta, assetCodes);
        Object existingWarnings = enriched.get("warnings");
        List<String> mergedWarnings = new ArrayList<>();
        if (existingWarnings instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof String s && !s.isBlank()) {
                    mergedWarnings.add(s);
                }
            }
        }
        for (String warning : warnings) {
            if (!mergedWarnings.contains(warning)) {
                mergedWarnings.add(warning);
            }
        }
        if (!providers.isEmpty()) {
            enriched.put("provider_name", providers.size() == 1
                    ? providers.iterator().next()
                    : (providers.stream().anyMatch(this::containsMockProvider) ? "MIXED_WITH_MOCK" : "MIXED"));
            enriched.put("provider_names", providers.stream().toList());
            enriched.put("mock_provider_warning", providers.stream().anyMatch(this::containsMockProvider));
        }
        if (delayed) {
            enriched.put("is_delayed", true);
        }
        enriched.put("warnings", mergedWarnings);
        return enriched;
    }

    private String normalizeProviderName(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean containsMockProvider(String providerName) {
        return providerName != null && providerName.toUpperCase(Locale.ROOT).contains("MOCK");
    }

    private void enrichPanelSelectionMeta(
            Map<String, Object> meta,
            String requestedTheme,
            List<TradingSignalViewDto> data) {
        if (meta == null) {
            return;
        }
        List<TradingSignalViewDto> rows = data == null ? List.of() : data;
        String requestedThemeCode = tradingSignalEngineService.normalizeThemeForApi(requestedTheme);
        boolean explicitThemeRequested = requestedThemeCode != null
                && !requestedThemeCode.isBlank()
                && !"ALL".equalsIgnoreCase(requestedThemeCode);

        Map<String, Long> themeCodeDistribution = new LinkedHashMap<>();
        Map<String, Long> strategyScopeDistribution = new LinkedHashMap<>();
        Map<String, Long> assetCodeDistribution = new LinkedHashMap<>();
        Map<String, Long> familyDistribution = new LinkedHashMap<>();
        LinkedHashSet<String> selectedThemeCodes = new LinkedHashSet<>();

        int selectedThemeMatchCount = 0;
        int coreThemeFilterAppliedCount = 0;
        int panelCooldownActiveCount = 0;

        for (TradingSignalViewDto row : rows) {
            if (row == null) {
                continue;
            }
            String themeCode = normalizePanelThemeCode(row.getThemeCode());
            String strategyScope = normalizePanelMetaToken(row.getStrategyScope(), "ALL");
            String assetCode = normalizePanelMetaToken(row.getAssetCode(), "");
            String familyKey = assetFamilyKey(assetCode);

            selectedThemeCodes.add(themeCode);
            incrementCount(themeCodeDistribution, themeCode);
            incrementCount(strategyScopeDistribution, strategyScope);
            if (!assetCode.isBlank()) {
                incrementCount(assetCodeDistribution, assetCode);
                incrementCount(familyDistribution, familyKey);
            }
            if (explicitThemeRequested && requestedThemeCode.equalsIgnoreCase(themeCode)) {
                selectedThemeMatchCount++;
            }
            if (Boolean.TRUE.equals(row.getCoreThemeFilterApplied())) {
                coreThemeFilterAppliedCount++;
            }
            if (Boolean.TRUE.equals(row.getPanelCooldownActive())) {
                panelCooldownActiveCount++;
            }
        }

        long duplicateAssetRows = duplicateRowCount(assetCodeDistribution);
        long repeatedFamilyCount = familyDistribution.values().stream().filter(count -> count > 1L).count();
        long duplicateFamilyRows = duplicateRowCount(familyDistribution);
        boolean themeSelectionReflected = !explicitThemeRequested || selectedThemeMatchCount > 0 || rows.isEmpty();

        List<String> warnings = new ArrayList<>();
        Object existingWarnings = meta.get("warnings");
        if (existingWarnings instanceof List<?> warningRows) {
            for (Object warning : warningRows) {
                if (warning instanceof String s && !s.isBlank() && !warnings.contains(s)) {
                    warnings.add(s);
                }
            }
        }
        if (explicitThemeRequested && !rows.isEmpty() && selectedThemeMatchCount == 0) {
            warnings.add("핵심분야 선택값(theme=" + requestedThemeCode + ")이 패널 결과에 반영되지 않았습니다.");
        } else if (explicitThemeRequested && selectedThemeMatchCount > 0 && selectedThemeMatchCount < rows.size()) {
            warnings.add("핵심분야 선택값(theme=" + requestedThemeCode + ")이 일부 결과에만 반영되었습니다(중복억제/완화 fallback 가능).");
        }
        if (duplicateAssetRows > 0L) {
            warnings.add("전략패널 결과에 동일 종목 중복 " + duplicateAssetRows + "건이 감지되었습니다.");
        }
        if (repeatedFamilyCount > 0L) {
            warnings.add("전략패널 결과에 동일 종목군 반복이 감지되었습니다(" + repeatedFamilyCount + "개 패밀리).");
        }
        if (panelCooldownActiveCount > 0) {
            warnings.add("패널 결과에 최근 노출 쿨다운 상태 종목이 포함되었습니다(" + panelCooldownActiveCount + "건).");
        }

        meta.put("requested_theme_code", explicitThemeRequested ? requestedThemeCode : "ALL");
        meta.put("theme_selection_reflected", themeSelectionReflected);
        meta.put("selected_theme_match_count", selectedThemeMatchCount);
        meta.put("selected_count", rows.size());
        meta.put("selected_theme_codes", new ArrayList<>(selectedThemeCodes));
        meta.put("theme_code_distribution", themeCodeDistribution);
        meta.put("strategy_scope_distribution", strategyScopeDistribution);
        meta.put("core_theme_filter_applied_count", coreThemeFilterAppliedCount);
        meta.put("duplicate_asset_rows", duplicateAssetRows);
        meta.put("repeated_family_count", repeatedFamilyCount);
        meta.put("duplicate_family_rows", duplicateFamilyRows);
        meta.put("panel_cooldown_active_count", panelCooldownActiveCount);
        meta.put("warnings", warnings);
    }

    private String normalizePanelThemeCode(String themeCode) {
        return normalizePanelMetaToken(themeCode, "UNSPECIFIED");
    }

    private String normalizePanelMetaToken(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private void incrementCount(Map<String, Long> counter, String key) {
        counter.put(key, counter.getOrDefault(key, 0L) + 1L);
    }

    private long duplicateRowCount(Map<String, Long> counter) {
        long duplicateRows = 0L;
        for (Long count : counter.values()) {
            if (count != null && count > 1L) {
                duplicateRows += (count - 1L);
            }
        }
        return duplicateRows;
    }

    private String assetFamilyKey(String assetCode) {
        if (assetCode == null || assetCode.isBlank()) {
            return "";
        }
        String normalized = assetCode.trim().toUpperCase(Locale.ROOT);
        int colonIndex = normalized.indexOf(':');
        if (colonIndex > 0) {
            normalized = normalized.substring(0, colonIndex);
        }
        int dotIndex = normalized.indexOf('.');
        if (dotIndex > 0) {
            normalized = normalized.substring(0, dotIndex);
        }
        int dashIndex = normalized.indexOf('-');
        if (dashIndex > 0) {
            normalized = normalized.substring(0, dashIndex);
        }
        return normalized;
    }

    /**
     * 국가별 뉴스 연관 주식 시그널(개인 참고용) 조회.
     */
    @GetMapping("/insight/stocks")
    public ApiEnvelope<List<StockSignalDto>> getStockSignals(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{2,5}$") String country,
            @RequestParam(defaultValue = "24h") String period,
            @RequestParam(defaultValue = "5") @Min(1) @Max(10) int limit) {
        List<StockSignalDto> data = stockSignalService.buildSignals(country, period, limit);
        return ApiEnvelope.<List<StockSignalDto>>builder()
                .data(data)
                .meta(metaWithStandardFields(
                        Map.of(
                                "country", country,
                                "period", period,
                                "limit", limit,
                                "model", "rule-heuristic-v1")))
                .traceId(traceId())
                .build();
    }

    /**
     * 단타 시그널 패널.
     */
    @GetMapping("/insight/signals/scalp")
    public ApiEnvelope<List<TradingSignalViewDto>> getScalpSignals(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{2,5}$") String country,
            @RequestParam(required = false) String theme,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        List<TradingSignalViewDto> data = tradingSignalEngineService.getScalpSignals(country, theme, limit);
        String normalizedThemeCode = normalizePanelMetaToken(tradingSignalEngineService.normalizeThemeForApi(theme), "ALL");
        Map<String, Object> meta = metaWithMarketDataContext(
                Map.of(
                        "country", country,
                        "theme", theme == null ? "" : theme,
                        "theme_code", normalizedThemeCode,
                        "limit", limit,
                        "panel", "scalp",
                        "selection_policy", "universe-dedup-v2"),
                data.stream().map(TradingSignalViewDto::getAssetCode).toList());
        enrichPanelSelectionMeta(meta, theme, data);
        return ApiEnvelope.<List<TradingSignalViewDto>>builder()
                .data(data)
                .meta(meta)
                .traceId(traceId())
                .build();
    }

    /**
     * 중기/일매매 시그널 패널.
     */
    @GetMapping("/insight/signals/swing")
    public ApiEnvelope<List<TradingSignalViewDto>> getSwingSignals(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{2,5}$") String country,
            @RequestParam(required = false) String theme,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        List<TradingSignalViewDto> data = tradingSignalEngineService.getSwingSignals(country, theme, limit);
        String normalizedThemeCode = normalizePanelMetaToken(tradingSignalEngineService.normalizeThemeForApi(theme), "ALL");
        Map<String, Object> meta = metaWithMarketDataContext(
                Map.of(
                        "country", country,
                        "theme", theme == null ? "" : theme,
                        "theme_code", normalizedThemeCode,
                        "limit", limit,
                        "panel", "swing",
                        "selection_policy", "universe-dedup-v2"),
                data.stream().map(TradingSignalViewDto::getAssetCode).toList());
        enrichPanelSelectionMeta(meta, theme, data);
        return ApiEnvelope.<List<TradingSignalViewDto>>builder()
                .data(data)
                .meta(meta)
                .traceId(traceId())
                .build();
    }

    /**
     * 차트 대응(포지션) 시그널 조회.
     */
    @GetMapping("/insight/signals/position")
    public ApiEnvelope<TradingSignalViewDto> getPositionSignal(
            @RequestParam("assetCode") @NotBlank String assetCode) {
        TradingSignalViewDto data = tradingSignalEngineService.getPositionSignal(assetCode);
        return ApiEnvelope.<TradingSignalViewDto>builder()
                .data(data)
                .meta(metaWithMarketDataContext(
                        Map.of("asset_code", assetCode, "panel", "position"),
                        List.of(data == null ? assetCode : data.getAssetCode())))
                .traceId(traceId())
                .build();
    }

    /**
     * 6개월 발굴 후보 조회.
     */
    @GetMapping("/insight/discovery")
    public ApiEnvelope<List<TradingSignalViewDto>> getDiscoverySignals(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{2,5}$") String country,
            @RequestParam(required = false) String theme,
            @RequestParam(defaultValue = "6m") String period,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        List<TradingSignalViewDto> data = tradingSignalEngineService.getDiscoverySignals(country, theme, limit);
        String normalizedThemeCode = normalizePanelMetaToken(tradingSignalEngineService.normalizeThemeForApi(theme), "ALL");
        Map<String, Object> meta = metaWithMarketDataContext(
                Map.of(
                        "country", country,
                        "theme", theme == null ? "" : theme,
                        "theme_code", normalizedThemeCode,
                        "period", period,
                        "limit", limit,
                        "panel", "discovery",
                        "selection_policy", "universe-dedup-v2"),
                data.stream().map(TradingSignalViewDto::getAssetCode).toList());
        enrichPanelSelectionMeta(meta, theme, data);
        return ApiEnvelope.<List<TradingSignalViewDto>>builder()
                .data(data)
                .meta(meta)
                .traceId(traceId())
                .build();
    }

    /**
     * 자산별 주간 컨텍스트 점수.
     */
    @GetMapping("/insight/assets/{assetCode}/weekly-context")
    public ApiEnvelope<WeeklyContextDto> getWeeklyContext(@PathVariable String assetCode) {
        WeeklyContextDto data = tradingSignalEngineService.getWeeklyContext(assetCode);
        return ApiEnvelope.<WeeklyContextDto>builder()
                .data(data)
                .meta(Map.of("asset_code", assetCode))
                .traceId(traceId())
                .build();
    }

    /**
     * 자산별 압력 탐지 결과.
     */
    @GetMapping("/insight/assets/{assetCode}/pressure-analysis")
    public ApiEnvelope<PressureAnalysisDto> getPressureAnalysis(@PathVariable String assetCode) {
        PressureAnalysisDto data = tradingSignalEngineService.getPressureAnalysis(assetCode);
        return ApiEnvelope.<PressureAnalysisDto>builder()
                .data(data)
                .meta(Map.of("asset_code", assetCode))
                .traceId(traceId())
                .build();
    }

    /**
     * 시그널 상세 팝업 정보.
     */
    @GetMapping("/insight/signals/{signalId}")
    public ApiEnvelope<SignalDetailDto> getSignalDetail(
            @PathVariable String signalId,
            @RequestParam(name = "assistant", defaultValue = "true") boolean assistant) {
        SignalDetailDto data = tradingSignalEngineService.getSignalDetail(signalId, assistant);
        return ApiEnvelope.<SignalDetailDto>builder()
                .data(data)
                .meta(metaWithMarketDataContext(
                        Map.of("signal_id", signalId, "assistant", assistant),
                        List.of(data == null ? "" : data.getAssetCode())))
                .traceId(traceId())
                .build();
    }

    /**
     * AI 비서 화면용 대시보드 집계(뉴스 홈과 별도).
     */
    @GetMapping("/insight/assistant/dashboard")
    public ApiEnvelope<Map<String, Object>> getAssistantDashboard(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{2,5}$") String country,
            @RequestParam(required = false) String theme,
            @RequestParam(defaultValue = "8") @Min(1) @Max(20) int limit) {
        Map<String, Object> data = assistantDashboardService.buildDashboard(country, theme, limit);
        return ApiEnvelope.<Map<String, Object>>builder()
                .data(data)
                .meta(metaWithAssistantDashboardContext(
                        Map.of(
                                "country", country,
                                "theme", theme == null ? "" : theme,
                                "limit", limit,
                                "view", "assistant"),
                        data))
                .traceId(traceId())
                .build();
    }

    /**
     * 정책 전/후 백테스트 비교 리포트.
     */
    @GetMapping("/insight/backtest/compare")
    public ApiEnvelope<BacktestComparisonDto> getBacktestComparison(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{2,5}$") String country,
            @RequestParam(required = false) String theme,
            @RequestParam(defaultValue = "30d") String period,
            @RequestParam(name = "validation_mode", required = false) String validationMode) {
        BacktestComparisonDto data = backtestComparisonService.compare(country, theme, period, validationMode);
        return ApiEnvelope.<BacktestComparisonDto>builder()
                .data(data)
                .meta(Map.of(
                        "country", country,
                        "theme", theme == null ? "" : theme,
                        "period", period,
                        "validation_mode", validationMode == null ? "" : validationMode))
                .traceId(traceId())
                .build();
    }

    /**
     * 저장된 백테스트 비교 리포트 목록 조회.
     */
    @GetMapping("/insight/backtest/reports")
    public ApiEnvelope<List<BacktestReportDto>> getBacktestReports(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{2,5}$") String country,
            @RequestParam(required = false) String theme,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        List<BacktestReportDto> data = backtestComparisonService.getReports(country, theme, limit);
        return ApiEnvelope.<List<BacktestReportDto>>builder()
                .data(data)
                .meta(Map.of(
                        "country", country,
                        "theme", theme == null ? "" : theme,
                        "limit", limit))
                .traceId(traceId())
                .build();
    }
}

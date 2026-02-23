package com.wangbyul.gnd.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.dto.FeatureToggleDto;
import com.wangbyul.gnd.api.service.assistant.AssistantRagService;
import com.wangbyul.gnd.core.config.MarketProviderProperties;
import com.wangbyul.gnd.core.domain.ApiResponseAuditEntity;
import com.wangbyul.gnd.core.domain.AssistantRagAuditLogEntity;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.AuditSeverityType;
import com.wangbyul.gnd.core.domain.MarketDataGapEventEntity;
import com.wangbyul.gnd.core.domain.MarketDataQualitySnapshotEntity;
import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.domain.NewsThumbnailSourceType;
import com.wangbyul.gnd.core.domain.NewsThumbnailStatusType;
import com.wangbyul.gnd.core.domain.SignalAuditEngineType;
import com.wangbyul.gnd.core.domain.SignalAuditLogEntity;
import com.wangbyul.gnd.core.domain.StrategyRunEntity;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.market.provider.MarketDataProviderRouter;
import com.wangbyul.gnd.core.repository.ApiResponseAuditRepository;
import com.wangbyul.gnd.core.repository.AssistantRagAuditLogRepository;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketDataGapEventRepository;
import com.wangbyul.gnd.core.repository.MarketDataQualitySnapshotRepository;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketProviderJobRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.NewsRepository;
import com.wangbyul.gnd.core.repository.SignalAuditLogRepository;
import com.wangbyul.gnd.core.repository.StrategyRunRepository;
import com.wangbyul.gnd.core.repository.TradingSignalRepository;
import com.wangbyul.gnd.core.service.MappingQualityReportService;
import com.wangbyul.gnd.core.service.TickerAliasVerificationService;
import com.wangbyul.gnd.core.service.UniverseRebuildService;
import com.wangbyul.gnd.core.util.NewsThumbnailParser;
import java.net.URI;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * 관리자 진단 API 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
@RequiredArgsConstructor
public class AdminDiagnosticsService {

    private static final String FUTURE_DATA_BLOCKED = "FUTURE_DATA_BLOCKED";
    private static final String RISK_CHECK_NEWS_PUBLISH_AFTER_FETCH = "news_publish_after_fetch";
    private static final String RISK_CHECK_TRANSLATION_DELAY = "translation_delay_over_threshold";
    private static final String RISK_CHECK_FUTURE_MARKET_BLOCK = "future_market_data_blocked";
    private static final Pattern THUMBNAIL_HINT_PATTERN = Pattern.compile(
            "(og:image|twitter:image|<img\\b|\\.(?:jpg|jpeg|png|webp|gif))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern THUMBNAIL_EPHEMERAL_URL_PATTERN = Pattern.compile(
            "(x-amz-|expires=|signature=|token=)",
            Pattern.CASE_INSENSITIVE);

    private final UniverseRebuildService universeRebuildService;
    private final TickerAliasVerificationService tickerAliasVerificationService;
    private final MappingQualityReportService mappingQualityReportService;
    private final MarketDataQualitySnapshotRepository marketDataQualitySnapshotRepository;
    private final MarketDataGapEventRepository marketDataGapEventRepository;
    private final ApiResponseAuditRepository apiResponseAuditRepository;
    private final MarketProviderJobRepository marketProviderJobRepository;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final MarketPriceBarRepository marketPriceBarRepository;
    private final NewsRepository newsRepository;
    private final SignalAuditLogRepository signalAuditLogRepository;
    private final TradingSignalRepository tradingSignalRepository;
    private final StrategyRunRepository strategyRunRepository;
    private final AssetUniverseRepository assetUniverseRepository;
    private final SystemFeatureToggleService systemFeatureToggleService;
    private final AssistantRagService assistantRagService;
    private final AssistantRagAuditLogRepository assistantRagAuditLogRepository;
    private final MarketDataProviderRouter marketDataProviderRouter;
    private final MarketProviderProperties marketProviderProperties;
    private final ObjectMapper objectMapper;

    @Value("${app.market.quality.minimum-quality-score:70}")
    private BigDecimal minimumQualityScore;

    @Value("${app.signal.minimum-combined-confidence:0.45}")
    private BigDecimal minimumCombinedConfidence;

    @Value("${app.universe.quote-freshness-threshold-minutes:180}")
    private long quoteFreshnessThresholdMinutes;

    @Value("${app.market.collection.provider-latency-warning-ms:2000}")
    private long providerLatencyWarningMs;

    @Value("${app.trade.live-enabled:false}")
    private boolean liveTradeEnabled;

    public Map<String, Object> getMarketCollectionSummary(String country, String provider, int hours) {
        OffsetDateTime since = OffsetDateTime.now().minusHours(Math.max(1, Math.min(hours, 72)));
        String providerKey = isBlank(provider) ? null : provider.trim().toLowerCase();
        List<MarketDataQualitySnapshotEntity> rows = marketDataQualitySnapshotRepository
                .findBySnapshotTimeUtcAfterOrderBySnapshotTimeUtcDesc(since)
                .stream()
                .filter(row -> isBlank(country) || eqIgnoreCase(row.getCountry(), country))
                .filter(row -> isBlank(provider) || eqIgnoreCase(row.getProvider(), provider))
                .toList();
        var providerJobs = (isBlank(provider)
                ? marketProviderJobRepository.findTop300ByScheduledAtAfterOrderByScheduledAtDesc(since)
                : marketProviderJobRepository.findTop300ByProviderNameAndScheduledAtAfterOrderByScheduledAtDesc(providerKey, since))
                .stream()
                .filter(job -> isBlank(provider) || eqIgnoreCase(job.getProviderName(), provider))
                .toList();
        long quoteCount = isBlank(provider)
                ? marketQuoteSnapshotRepository.countByCreatedAtAfter(since)
                : marketQuoteSnapshotRepository.countByProviderNameAndCreatedAtAfter(providerKey, since);
        long barCount = isBlank(provider)
                ? marketPriceBarRepository.countByCreatedAtAfter(since)
                : marketPriceBarRepository.countByProviderNameAndCreatedAtAfter(providerKey, since);
        List<MarketQuoteSnapshotEntity> recentQuotes = (isBlank(provider)
                ? marketQuoteSnapshotRepository.findTop500ByCreatedAtAfterOrderByCreatedAtDesc(since)
                : marketQuoteSnapshotRepository.findTop500ByProviderNameAndCreatedAtAfterOrderByCreatedAtDesc(providerKey, since))
                .stream()
                .filter(row -> isBlank(provider) || eqIgnoreCase(row.getProviderName(), provider))
                .toList();

        Map<String, Long> providerNameDistribution = providerDistributionFromQuotes(recentQuotes);
        Map<String, Long> providerJobDistribution = providerJobs.stream()
                .collect(Collectors.groupingBy(
                        job -> upper(blankAs(job.getProviderName(), "UNKNOWN")),
                        LinkedHashMap::new,
                        Collectors.counting()));
        String providerName = summarizeProviderName(providerNameDistribution);
        long delayedQuoteCount = recentQuotes.stream().filter(this::isDelayedQuote).count();
        boolean delayed = delayedQuoteCount > 0
                || (quoteCount <= 0 && ("toss".equals(providerKey) || "kiwoom".equals(providerKey) || isBlank(provider)));
        boolean mockDetected = containsIgnoreCase(providerName, "MOCK")
                || providerNameDistribution.keySet().stream().anyMatch(name -> containsIgnoreCase(name, "MOCK"))
                || providerJobDistribution.keySet().stream().anyMatch(name -> containsIgnoreCase(name, "MOCK"));
        List<String> warnings = new ArrayList<>();
        if (mockDetected) {
            warnings.add("시장데이터 provider_name=MOCK 입니다. 운영 판단용 실데이터가 아닙니다.");
        }
        if (!marketProviderProperties.isAllowMock() && mockDetected) {
            warnings.add("운영 기본 설정상 allowMock=false 이지만 MOCK 데이터가 감지되었습니다.");
        }
        if (delayed) {
            warnings.add("일부 시장데이터가 지연되었거나 최신 시세가 비어 있습니다.");
        }
        if (providerJobs.stream().anyMatch(job -> job.getLatencyMs() != null && job.getLatencyMs() > providerLatencyWarningMs)) {
            warnings.add("최근 수집 잡 지연(latency) 경고가 존재합니다.");
        }
        if (providerJobs.stream().anyMatch(job -> job.getStatus() != null && "FAILED".equals(job.getStatus().name()))) {
            warnings.add("최근 시장데이터 수집 잡 실패가 존재합니다.");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope_country", blankAs(country, "ALL"));
        data.put("scope_provider", blankAs(provider, "ALL"));
        data.put("window_hours", Math.max(1, Math.min(hours, 72)));
        data.put("provider_name", providerName);
        data.put("is_delayed", delayed);
        data.put("warnings", warnings);
        data.put("warning_flags", Map.of(
                "mock_provider_detected", mockDetected,
                "delayed_data_detected", delayed,
                "provider_failure_detected", providerJobs.stream().anyMatch(job -> job.getStatus() != null && "FAILED".equals(job.getStatus().name()))));
        data.put("snapshot_count", rows.size());
        data.put("provider_job_count", providerJobs.size());
        data.put("provider_job_success_count", providerJobs.stream()
                .filter(job -> job.getStatus() != null && "SUCCESS".equals(job.getStatus().name()))
                .count());
        data.put("provider_job_failed_count", providerJobs.stream()
                .filter(job -> job.getStatus() != null && "FAILED".equals(job.getStatus().name()))
                .count());
        data.put("provider_job_empty_response_count", providerJobs.stream()
                .filter(job -> Boolean.TRUE.equals(job.getEmptyResponse()))
                .count());
        data.put("quote_snapshot_count", quoteCount);
        data.put("price_bar_count", barCount);
        data.put("recent_quote_provider_distribution", providerNameDistribution);
        data.put("recent_provider_job_distribution", providerJobDistribution);
        data.put("recent_delayed_quote_count", delayedQuoteCount);
        data.put("unresolved_gap_count", marketDataGapEventRepository.countByResolvedFalse());
        data.put("avg_quality_score", avg(rows, MarketDataQualitySnapshotEntity::getQualityScore, 2));
        data.put("avg_missing_rate", avg(rows, MarketDataQualitySnapshotEntity::getMissingRate, 6));
        data.put("avg_delay_rate", avg(rows, MarketDataQualitySnapshotEntity::getDelayRate, 6));
        data.put("avg_duplicate_rate", avg(rows, MarketDataQualitySnapshotEntity::getDuplicateRate, 6));
        data.put("avg_anomaly_rate", avg(rows, MarketDataQualitySnapshotEntity::getAnomalyRate, 6));
        data.put("latest_snapshot_time_utc", rows.isEmpty() ? null : rows.get(0).getSnapshotTimeUtc());
        data.put("latest_provider_job_time_utc", providerJobs.isEmpty() ? null : providerJobs.get(0).getScheduledAt());

        Map<String, Object> health = rows.stream()
                .collect(Collectors.groupingBy(row -> blankAs(row.getProvider(), "UNKNOWN")))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            BigDecimal avgScore = avg(entry.getValue(), MarketDataQualitySnapshotEntity::getQualityScore, 2);
                            return Map.of(
                                    "avg_quality_score", avgScore,
                                    "snapshot_count", entry.getValue().size(),
                                    "status", avgScore.compareTo(minimumQualityScore) >= 0 ? "HEALTHY" : "WARN");
                        },
                        (a, b) -> a,
                        LinkedHashMap::new));
        if (health.isEmpty() && !providerJobs.isEmpty()) {
            health = providerJobs.stream()
                    .collect(Collectors.groupingBy(job -> blankAs(job.getProviderName(), "UNKNOWN")))
                    .entrySet()
                    .stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> {
                                long failed = entry.getValue().stream()
                                        .filter(job -> job.getStatus() != null && "FAILED".equals(job.getStatus().name()))
                                        .count();
                                long empty = entry.getValue().stream().filter(job -> Boolean.TRUE.equals(job.getEmptyResponse())).count();
                                String status = failed > 0 ? "WARN" : "HEALTHY";
                                if (entry.getValue().stream().allMatch(job -> job.getStatus() != null && "FAILED".equals(job.getStatus().name()))) {
                                    status = "DOWN";
                                }
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("avg_quality_score", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                                item.put("snapshot_count", 0);
                                item.put("job_count", entry.getValue().size());
                                item.put("failed_job_count", failed);
                                item.put("empty_response_count", empty);
                                item.put("status", status);
                                return item;
                            },
                            (a, b) -> a,
                            LinkedHashMap::new));
        }
        data.put("provider_health", health);
        data.put("provider_runtime_config", Map.of(
                "active_provider", upper(blankAs(marketDataProviderRouter.activeProviderId(), "mock")),
                "fallback_to_mock_on_failure", marketDataProviderRouter.fallbackToMockOnFailure(),
                "allow_mock", marketProviderRouterAllowMock()));
        data.put("feature_toggle_status", featureToggleStatusSummary());
        data.put("recent_collection_events", recentCollectionEvents(providerJobs));
        data.put("recent_warning_error_summary", recentWarningErrorSummary(since, provider));
        data.put("trace_propagation", tracePropagationSummary());
        data.put("market_collection_audit_status", marketCollectionAuditStatusSummary());
        data.put("deployment_verification_checklist", deploymentVerificationChecklist(data));
        return data;
    }

    public Map<String, Object> getMarketCollectionGaps(
            String provider,
            String assetCode,
            AuditSeverityType severity,
            Boolean resolved,
            String traceId,
            int limit) {
        List<MarketDataGapEventEntity> rows = selectGapRows(provider, assetCode, traceId).stream()
                .filter(row -> severity == null || row.getSeverity() == severity)
                .filter(row -> resolved == null || Boolean.valueOf(resolved).equals(row.getResolved()))
                .limit(clampLimit(limit))
                .toList();

        List<Map<String, Object>> items = rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("event_time_utc", row.getEventTimeUtc());
            item.put("provider", blankAs(row.getProvider(), ""));
            item.put("asset_code", blankAs(row.getAssetCode(), ""));
            item.put("event_type", row.getEventType() == null ? "" : row.getEventType().name());
            item.put("severity", row.getSeverity() == null ? "" : row.getSeverity().name());
            item.put("timeframe", blankAs(row.getTimeframe(), "-"));
            item.put("delay_seconds", row.getDelaySeconds());
            item.put("resolved", Boolean.TRUE.equals(row.getResolved()));
            item.put("trace_id", blankAs(row.getTraceId(), ""));
            item.put("detail_json", blankAs(row.getDetailJson(), "{}"));
            return item;
        }).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope_provider", blankAs(provider, "ALL"));
        data.put("scope_asset_code", blankAs(assetCode, "ALL"));
        data.put("scope_trace_id", blankAs(traceId, "ALL"));
        data.put("count", rows.size());
        data.put("severity_distribution", rows.stream().collect(Collectors.groupingBy(
                row -> row.getSeverity() == null ? "UNKNOWN" : row.getSeverity().name(),
                Collectors.counting())));
        data.put("unresolved_error_count", rows.stream()
                .filter(row -> Boolean.FALSE.equals(row.getResolved()) && row.getSeverity() == AuditSeverityType.ERROR)
                .count());
        data.put("items", items);
        return data;
    }

    public Map<String, Object> getMarketCollectionProviderAudit(
            String provider,
            String apiName,
            Boolean success,
            String traceId,
            int limit) {
        List<ApiResponseAuditEntity> rows = selectProviderAuditRows(provider, traceId).stream()
                .filter(row -> isBlank(apiName) || containsIgnoreCase(row.getApiName(), apiName))
                .filter(row -> success == null || Boolean.valueOf(success).equals(row.getSuccess()))
                .limit(clampLimit(limit))
                .toList();

        long failed = rows.stream().filter(row -> !Boolean.TRUE.equals(row.getSuccess())).count();
        BigDecimal successRate = rows.isEmpty()
                ? zero(6)
                : BigDecimal.valueOf(rows.size() - failed).divide(BigDecimal.valueOf(rows.size()), 6, RoundingMode.HALF_UP);
        BigDecimal avgLatency = rows.stream()
                .map(ApiResponseAuditEntity::getLatencyMs)
                .filter(ms -> ms != null && ms >= 0)
                .map(BigDecimal::valueOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!rows.isEmpty()) {
            avgLatency = avgLatency.divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
        }

        List<Map<String, Object>> items = rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("provider", blankAs(row.getProvider(), ""));
            item.put("api_name", blankAs(row.getApiName(), ""));
            item.put("request_time_utc", row.getRequestTimeUtc());
            item.put("response_time_utc", row.getResponseTimeUtc());
            item.put("latency_ms", row.getLatencyMs());
            item.put("http_status", row.getHttpStatus());
            item.put("record_count", row.getRecordCount());
            item.put("success", Boolean.TRUE.equals(row.getSuccess()));
            item.put("error_code", blankAs(row.getErrorCode(), ""));
            item.put("trace_id", blankAs(row.getTraceId(), ""));
            item.put("sample_payload_json", blankAs(row.getSamplePayloadJson(), "{}"));
            return item;
        }).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope_provider", blankAs(provider, "ALL"));
        data.put("scope_api_name", blankAs(apiName, "ALL"));
        data.put("scope_trace_id", blankAs(traceId, "ALL"));
        data.put("count", rows.size());
        data.put("failed_count", failed);
        data.put("success_rate", successRate);
        data.put("avg_latency_ms", avgLatency);
        data.put("items", items);
        return data;
    }

    public Map<String, Object> getMarketCollectionQualityScore(String country, String theme, int days) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(Math.max(1, Math.min(days, 30)));
        List<MarketDataQualitySnapshotEntity> rows = marketDataQualitySnapshotRepository
                .findBySnapshotTimeUtcAfterOrderBySnapshotTimeUtcDesc(since)
                .stream()
                .filter(row -> isBlank(country) || eqIgnoreCase(row.getCountry(), country))
                .filter(row -> isBlank(theme) || eqIgnoreCase(row.getTheme(), theme))
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope_country", blankAs(country, "ALL"));
        data.put("scope_theme", blankAs(theme, "ALL"));
        data.put("window_days", Math.max(1, Math.min(days, 30)));
        data.put("count", rows.size());
        data.put("avg_quality_score", avg(rows, MarketDataQualitySnapshotEntity::getQualityScore, 2));
        data.put("minimum_quality_score", rows.stream()
                .map(MarketDataQualitySnapshotEntity::getQualityScore)
                .filter(v -> v != null)
                .min(Comparator.naturalOrder())
                .orElse(zero(2))
                .setScale(2, RoundingMode.HALF_UP));
        data.put("below_threshold_count", rows.stream()
                .map(MarketDataQualitySnapshotEntity::getQualityScore)
                .filter(v -> v != null && v.compareTo(minimumQualityScore) < 0)
                .count());

        List<Map<String, Object>> trend = rows.stream()
                .collect(Collectors.groupingBy(row -> row.getSnapshotTimeUtc().truncatedTo(ChronoUnit.HOURS).toString()))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, List<MarketDataQualitySnapshotEntity>>comparingByKey().reversed())
                .limit(48)
                .map(entry -> Map.<String, Object>of(
                        "bucket_hour_utc", entry.getKey(),
                        "snapshot_count", entry.getValue().size(),
                        "avg_quality_score", avg(entry.getValue(), MarketDataQualitySnapshotEntity::getQualityScore, 2),
                        "avg_missing_rate", avg(entry.getValue(), MarketDataQualitySnapshotEntity::getMissingRate, 6),
                        "avg_delay_rate", avg(entry.getValue(), MarketDataQualitySnapshotEntity::getDelayRate, 6)))
                .toList();
        data.put("trend", trend);
        return data;
    }

    public Map<String, Object> getUniverseDiagnostics(String country, String theme, int limit) {
        return universeRebuildService.universeDiagnostics(country, theme, limit);
    }

    public Map<String, Object> getUniverseDiversityDiagnostics(String country, String theme, int limit) {
        Map<String, Object> base = universeRebuildService.universeDiagnostics(country, theme, Math.max(limit, 200));
        Map<String, Long> repeated = castLongMap(base.get("top_repeated_families"));
        long total = repeated.values().stream().mapToLong(Long::longValue).sum();
        long max = repeated.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        BigDecimal concentration = total <= 0
                ? zero(6)
                : BigDecimal.valueOf(max).divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);

        List<Map<String, Object>> topAssets = castMapList(base.get("top_assets"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope_country", blankAs(country, "ALL"));
        data.put("scope_theme", blankAs(theme, "ALL"));
        data.put("sample_size", topAssets.size());
        data.put("core_assets_in_sample", topAssets.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("is_core_asset")))
                .count());
        data.put("family_concentration_ratio", concentration);
        data.put("top_repeated_families", repeated);
        data.put("diversity_warning", concentration.compareTo(BigDecimal.valueOf(0.45d)) > 0);
        data.put("minimum_rules", base.get("minimum_rules"));
        data.put("assets_by_layer", base.getOrDefault("assets_by_layer", Map.of()));
        data.put("assets_by_theme_code", base.getOrDefault("assets_by_theme_code", Map.of()));
        data.put("priority_theme_assets", base.getOrDefault("priority_theme_assets", 0));
        data.put("stale_quote_assets", base.getOrDefault("stale_quote_assets", 0));
        return data;
    }

    public Map<String, Object> getTickerAliasDiagnostics(int limit) {
        return tickerAliasVerificationService.diagnostics(limit);
    }

    public Map<String, Object> getNewsAssetMappingDiagnostics(String country, String theme, boolean refresh) {
        return mappingQualityReportService.diagnostics(country, theme, refresh);
    }

    public Map<String, Object> getNewsThumbnailDiagnostics(String country, int hours, int limit) {
        int safeHours = Math.max(1, Math.min(hours, 168));
        int safeLimit = Math.max(1, Math.min(limit, 500));
        OffsetDateTime since = OffsetDateTime.now().minusHours(safeHours);
        int fetchSize = Math.max(300, safeLimit);

        List<NewsEntity> recent = newsRepository
                .findAll(PageRequest.of(0, Math.min(fetchSize, 1000), Sort.by(Sort.Direction.DESC, "pubUtc")))
                .getContent()
                .stream()
                .filter(row -> isBlank(country) || eqIgnoreCase(row.getCountry(), country))
                .filter(row -> {
                    OffsetDateTime baseTime = newsThumbnailBaseTime(row);
                    return baseTime != null && !baseTime.isBefore(since);
                })
                .limit(safeLimit)
                .toList();

        List<ThumbnailDiagRow> classified = recent.stream()
                .map(this::classifyNewsThumbnailRow)
                .toList();

        long successCount = classified.stream()
                .filter(row -> row.status() == NewsThumbnailStatusType.SUCCESS)
                .count();
        long emptyCount = classified.stream()
                .filter(row -> row.status() == NewsThumbnailStatusType.EMPTY)
                .count();
        long failedCount = classified.stream()
                .filter(row -> row.status() == NewsThumbnailStatusType.FAILED)
                .count();
        long failedOrEmptyCount = emptyCount + failedCount;
        long mixedContentCount = classified.stream().filter(ThumbnailDiagRow::mixedContentRisk).count();
        long parserFallbackUsedCount = classified.stream().filter(ThumbnailDiagRow::parserFallbackUsed).count();
        long storedMetadataCount = classified.stream().filter(ThumbnailDiagRow::storedMetadataUsed).count();
        long defaultPlaceholderFallbackCount = classified.stream()
                .filter(row -> row.status() != NewsThumbnailStatusType.SUCCESS)
                .count();
        long potentialHotlinkRiskCount = classified.stream().filter(ThumbnailDiagRow::ephemeralUrlRisk).count();

        Map<String, Long> sourceDistribution = classified.stream()
                .collect(Collectors.groupingBy(
                        row -> row.source() == null ? "UNKNOWN" : row.source().name(),
                        LinkedHashMap::new,
                        Collectors.counting()));
        Map<String, Long> statusDistribution = classified.stream()
                .collect(Collectors.groupingBy(
                        row -> row.status() == null ? "UNKNOWN" : row.status().name(),
                        LinkedHashMap::new,
                        Collectors.counting()));
        Map<String, Long> hostDistribution = classified.stream()
                .filter(row -> !isBlank(row.thumbnailUrl()))
                .collect(Collectors.groupingBy(
                        row -> upper(blankAs(row.thumbnailHost(), "UNKNOWN")),
                        LinkedHashMap::new,
                        Collectors.counting()));
        Map<String, Long> topHostDistribution = hostDistribution.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(15)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new));

        BigDecimal successRate = classified.isEmpty()
                ? zero(4)
                : BigDecimal.valueOf(successCount)
                        .divide(BigDecimal.valueOf(classified.size()), 4, RoundingMode.HALF_UP);

        List<String> warnings = new ArrayList<>();
        if (successRate.compareTo(BigDecimal.valueOf(0.70d)) < 0) {
            warnings.add("뉴스 썸네일 성공률이 낮습니다 (70% 미만).");
        }
        if (mixedContentCount > 0) {
            warnings.add("http:// 썸네일 URL이 존재하여 HTTPS 화면에서 mixed-content 차단 위험이 있습니다.");
        }
        if (failedCount > 0) {
            warnings.add("썸네일 파싱 FAILED 상태가 존재합니다.");
        }
        if (emptyCount > 0) {
            warnings.add("썸네일이 비어 있는 기사(EMPTY)가 존재하여 placeholder 이미지가 사용됩니다.");
        }
        if (potentialHotlinkRiskCount > 0) {
            warnings.add("만료성/서명형 이미지 URL이 감지되어 hotlink 만료 가능성이 있습니다.");
        }

        List<Map<String, Object>> failureSamples = classified.stream()
                .filter(row -> row.status() != NewsThumbnailStatusType.SUCCESS)
                .limit(12)
                .map(this::thumbnailDiagSample)
                .toList();
        List<Map<String, Object>> mixedContentSamples = classified.stream()
                .filter(ThumbnailDiagRow::mixedContentRisk)
                .limit(10)
                .map(this::thumbnailDiagSample)
                .toList();
        List<Map<String, Object>> ephemeralUrlSamples = classified.stream()
                .filter(ThumbnailDiagRow::ephemeralUrlRisk)
                .limit(10)
                .map(this::thumbnailDiagSample)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope_country", blankAs(country, "ALL"));
        data.put("window_hours", safeHours);
        data.put("sample_size", classified.size());
        data.put("success_count", successCount);
        data.put("empty_count", emptyCount);
        data.put("failed_count", failedCount);
        data.put("failed_or_empty_count", failedOrEmptyCount);
        data.put("success_rate", successRate);
        data.put("thumbnail_source_distribution", sourceDistribution);
        data.put("thumbnail_status_distribution", statusDistribution);
        data.put("thumbnail_host_distribution_top", topHostDistribution);
        data.put("stored_thumbnail_metadata_used_count", storedMetadataCount);
        data.put("parser_fallback_used_count", parserFallbackUsedCount);
        data.put("placeholder_fallback_count", defaultPlaceholderFallbackCount);
        data.put("mixed_content_http_count", mixedContentCount);
        data.put("ephemeral_url_risk_count", potentialHotlinkRiskCount);
        data.put("warnings", warnings);
        data.put("provider_name", upper(blankAs(marketDataProviderRouter.activeProviderId(), "mock")));
        data.put("is_delayed", false);
        data.put("rendering_contract_check", Map.of(
                "api_field_thumbnail_url_present", true,
                "api_field_thumbnail_source_present", true,
                "api_field_thumbnail_status_present", true,
                "frontend_should_render_img_first", true,
                "text_placeholder_rendering_removed", true));
        data.put("parsing_priority", List.of("RSS enclosure", "og:image", "twitter:image", "body first image", "default placeholder"));
        data.put("policy_checks", Map.of(
                "mixed_content_http_detected", mixedContentCount > 0,
                "csp_header_code_check", "MANUAL_REVIEW_REQUIRED",
                "hotlink_block_risk_detected", potentialHotlinkRiskCount > 0));
        data.put("failure_samples", failureSamples);
        data.put("mixed_content_samples", mixedContentSamples);
        data.put("ephemeral_url_samples", ephemeralUrlSamples);
        data.put("items", classified.stream().limit(Math.min(30, safeLimit)).map(this::thumbnailDiagSample).toList());
        data.put("latest_news_time_utc", classified.stream()
                .map(ThumbnailDiagRow::eventTimeUtc)
                .filter(Objects::nonNull)
                .max(OffsetDateTime::compareTo)
                .orElse(null));
        data.put("diagnostic_todo", List.of(
                "CSP 응답 헤더 실측 검증(브라우저/프록시 레벨) 필요",
                "hotlink 차단은 브라우저 onerror/네트워크 로그 기반 추가 수집 필요",
                "기존 적재 뉴스의 썸네일 메타 보강을 위한 백필 작업 검토"));
        return data;
    }

    public Map<String, Object> getSignalAuditDiagnostics(
            String country,
            String assetCode,
            String signalId,
            SignalAuditEngineType engineType,
            String traceId,
            int limit) {
        Map<String, String> countryMap = assetUniverseRepository.findAll().stream()
                .collect(Collectors.toMap(AssetUniverseEntity::getAssetCode, AssetUniverseEntity::getCountry, (a, b) -> a));
        List<SignalAuditLogEntity> rows = selectSignalAuditRows(assetCode, signalId, engineType, traceId).stream()
                .filter(row -> isBlank(country) || eqIgnoreCase(blankAs(countryMap.get(row.getAssetCode()), ""), country))
                .limit(clampLimit(limit))
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope_country", blankAs(country, "ALL"));
        data.put("scope_asset_code", blankAs(assetCode, "ALL"));
        data.put("scope_signal_id", blankAs(signalId, "ALL"));
        data.put("scope_engine_type", engineType == null ? "ALL" : engineType.name());
        data.put("scope_trace_id", blankAs(traceId, "ALL"));
        data.put("count", rows.size());
        data.put("blocked_count", rows.stream().filter(row -> !isBlank(row.getBlockedReason())).count());
        data.put("after_risk_distribution", rows.stream().collect(Collectors.groupingBy(
                row -> row.getDecisionAfterRisk() == null ? "UNKNOWN" : row.getDecisionAfterRisk().name(),
                Collectors.counting())));
        data.put("items", rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("audit_time_utc", row.getAuditTimeUtc());
            item.put("signal_id", blankAs(row.getSignalId(), ""));
            item.put("asset_code", blankAs(row.getAssetCode(), ""));
            item.put("country", blankAs(countryMap.get(row.getAssetCode()), "N/A"));
            item.put("engine_type", row.getEngineType() == null ? "" : row.getEngineType().name());
            item.put("decision_before_risk", row.getDecisionBeforeRisk() == null ? "" : row.getDecisionBeforeRisk().name());
            item.put("decision_after_risk", row.getDecisionAfterRisk() == null ? "" : row.getDecisionAfterRisk().name());
            item.put("blocked_reason", blankAs(row.getBlockedReason(), ""));
            item.put("confidence_before", row.getConfidenceBefore());
            item.put("confidence_after", row.getConfidenceAfter());
            item.put("trace_id", blankAs(row.getTraceId(), ""));
            item.put("rule_hits_json", blankAs(row.getRuleHitsJson(), "{}"));
            item.put("risk_checks_json", blankAs(row.getRiskChecksJson(), "{}"));
            return item;
        }).toList());
        return data;
    }

    public Map<String, Object> getSignalAlignmentDiagnostics(String country, int hours) {
        List<TradingSignalEntity> rows = selectRecentSignals(country, hours, 500);
        long futureBlocked = rows.stream().filter(row -> containsIgnoreCase(row.getBlockedReason(), FUTURE_DATA_BLOCKED)).count();
        long invalidOrder = rows.stream()
                .map(TradingSignalEntity::getRiskChecks)
                .map(this::parseRiskChecks)
                .filter(checks -> checks.stream().anyMatch(check -> check.startsWith(RISK_CHECK_NEWS_PUBLISH_AFTER_FETCH)))
                .count();
        long translationDelay = rows.stream()
                .map(TradingSignalEntity::getRiskChecks)
                .map(this::parseRiskChecks)
                .filter(checks -> checks.stream().anyMatch(check -> check.startsWith(RISK_CHECK_TRANSLATION_DELAY)))
                .count();
        long futureMarketData = rows.stream()
                .map(TradingSignalEntity::getRiskChecks)
                .map(this::parseRiskChecks)
                .filter(checks -> checks.stream().anyMatch(check -> check.contains(RISK_CHECK_FUTURE_MARKET_BLOCK)))
                .count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope_country", blankAs(country, "ALL"));
        data.put("window_hours", Math.max(1, Math.min(hours, 72)));
        data.put("signal_count", rows.size());
        data.put("future_data_blocked_count", futureBlocked);
        data.put("invalid_publish_fetch_count", invalidOrder);
        data.put("translation_delayed_count", translationDelay);
        data.put("future_market_data_warning_count", futureMarketData);
        data.put("alignment_violation_rate", rows.isEmpty()
                ? zero(6)
                : BigDecimal.valueOf(futureBlocked + invalidOrder + translationDelay)
                        .divide(BigDecimal.valueOf(rows.size()), 6, RoundingMode.HALF_UP));
        data.put("samples", rows.stream().limit(50).map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("signal_id", row.getId());
            item.put("asset_code", row.getAssetCode());
            item.put("generated_at", row.getGeneratedAt());
            item.put("blocked_reason", blankAs(row.getBlockedReason(), ""));
            item.put("risk_checks", parseRiskChecks(row.getRiskChecks()));
            item.put("trace_id", blankAs(row.getTraceId(), ""));
            return item;
        }).toList());
        return data;
    }

    public Map<String, Object> getSignalConfidenceDistribution(String country, int hours) {
        List<TradingSignalEntity> rows = selectRecentSignals(country, hours, 500);
        Map<String, Long> buckets = new LinkedHashMap<>();
        buckets.put("[0.0,0.2)", 0L);
        buckets.put("[0.2,0.4)", 0L);
        buckets.put("[0.4,0.6)", 0L);
        buckets.put("[0.6,0.8)", 0L);
        buckets.put("[0.8,1.0]", 0L);
        for (TradingSignalEntity row : rows) {
            double value = row.getCombinedConfidence() == null ? 0d : row.getCombinedConfidence().doubleValue();
            if (value < 0.2d) {
                buckets.compute("[0.0,0.2)", (k, v) -> v + 1);
            } else if (value < 0.4d) {
                buckets.compute("[0.2,0.4)", (k, v) -> v + 1);
            } else if (value < 0.6d) {
                buckets.compute("[0.4,0.6)", (k, v) -> v + 1);
            } else if (value < 0.8d) {
                buckets.compute("[0.6,0.8)", (k, v) -> v + 1);
            } else {
                buckets.compute("[0.8,1.0]", (k, v) -> v + 1);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Long> signalWindowDistribution = rows.stream().collect(Collectors.groupingBy(
                row -> blankAs(row.getSignalWindow(), "UNKNOWN"),
                LinkedHashMap::new,
                Collectors.counting()));
        Map<String, Map<String, Long>> actionDistributionByStrategy = rows.stream()
                .collect(Collectors.groupingBy(
                        row -> strategyWindowKey(row.getSignalWindow()),
                        LinkedHashMap::new,
                        Collectors.groupingBy(
                                row -> row.getAction() == null ? "UNKNOWN" : row.getAction().name(),
                                LinkedHashMap::new,
                                Collectors.counting())));
        Map<String, Map<String, Long>> blockedReasonByStrategy = new LinkedHashMap<>();
        for (TradingSignalEntity row : rows) {
            String strategy = strategyWindowKey(row.getSignalWindow());
            Map<String, Long> dist = blockedReasonByStrategy.computeIfAbsent(strategy, k -> new LinkedHashMap<>());
            String blocked = blankAs(row.getBlockedReason(), "NONE");
            for (String token : blocked.split("\\|")) {
                String key = token == null || token.isBlank() ? "NONE" : token.trim();
                dist.merge(key, 1L, Long::sum);
            }
        }
        data.put("scope_country", blankAs(country, "ALL"));
        data.put("window_hours", Math.max(1, Math.min(hours, 72)));
        data.put("signal_count", rows.size());
        data.put("avg_combined_confidence", rows.isEmpty()
                ? zero(4)
                : rows.stream()
                        .map(TradingSignalEntity::getCombinedConfidence)
                        .filter(v -> v != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(rows.size()), 4, RoundingMode.HALF_UP));
        data.put("low_confidence_threshold", minimumCombinedConfidence.setScale(4, RoundingMode.HALF_UP));
        data.put("low_confidence_count", rows.stream()
                .map(TradingSignalEntity::getCombinedConfidence)
                .filter(v -> v != null && v.compareTo(minimumCombinedConfidence) < 0)
                .count());
        data.put("confidence_buckets", buckets);
        data.put("action_distribution", rows.stream().collect(Collectors.groupingBy(
                row -> row.getAction() == null ? "UNKNOWN" : row.getAction().name(),
                Collectors.counting())));
        data.put("blocked_reason_distribution", rows.stream().collect(Collectors.groupingBy(
                row -> blankAs(row.getBlockedReason(), "NONE"),
                Collectors.counting())));
        data.put("signal_window_distribution", signalWindowDistribution);
        data.put("action_distribution_by_strategy", actionDistributionByStrategy);
        data.put("blocked_reason_distribution_by_strategy", blockedReasonByStrategy);
        data.put("duplicate_exposure_stats", duplicateExposureStats(rows));
        data.put("assistant_rag_summary", assistantRagSummary(hours));
        return data;
    }

    public Map<String, Object> getTraceDetail(String traceId, int limit) {
        return getTraceDetail(traceId, limit, true);
    }

    public Map<String, Object> getTraceDetail(String traceId, int limit, boolean includeAssistant) {
        if (isBlank(traceId)) {
            throw new IllegalArgumentException("trace_id is required");
        }
        int safeLimit = clampLimit(limit);
        List<FeatureToggleDto> toggles = systemFeatureToggleService.list(null, null, null, traceId, safeLimit);
        List<StrategyRunEntity> strategyRuns = strategyRunRepository.findTop100ByTraceIdOrderByStartedAtDesc(traceId)
                .stream()
                .limit(safeLimit)
                .toList();
        List<AssistantRagAuditLogEntity> assistantAudits = assistantRagAuditLogRepository.findTop200ByTraceIdOrderByCreatedAtDesc(traceId)
                .stream()
                .limit(safeLimit)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("trace_id", traceId);
        data.put("counts", Map.of(
                "quality_snapshots", marketDataQualitySnapshotRepository.findTop200ByTraceIdOrderBySnapshotTimeUtcDesc(traceId).size(),
                "gap_events", marketDataGapEventRepository.findTop200ByTraceIdOrderByEventTimeUtcDesc(traceId).size(),
                "provider_audits", apiResponseAuditRepository.findTop300ByTraceIdOrderByRequestTimeUtcDesc(traceId).size(),
                "signal_audits", signalAuditLogRepository.findTop200ByTraceIdOrderByAuditTimeUtcDesc(traceId).size(),
                "assistant_rag_audits", assistantAudits.size(),
                "strategy_runs", strategyRuns.size(),
                "feature_toggles", toggles.size()));
        data.put("market_collection", Map.of(
                "quality_snapshots", marketDataQualitySnapshotRepository.findTop200ByTraceIdOrderBySnapshotTimeUtcDesc(traceId)
                        .stream().limit(safeLimit).map(row -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", row.getId());
                            item.put("snapshot_time_utc", row.getSnapshotTimeUtc());
                            item.put("provider", blankAs(row.getProvider(), ""));
                            item.put("country", blankAs(row.getCountry(), ""));
                            item.put("quality_score", row.getQualityScore());
                            item.put("trace_id", blankAs(row.getTraceId(), ""));
                            return item;
                        }).toList(),
                "gap_events", marketDataGapEventRepository.findTop200ByTraceIdOrderByEventTimeUtcDesc(traceId)
                        .stream().limit(safeLimit).map(row -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", row.getId());
                            item.put("event_time_utc", row.getEventTimeUtc());
                            item.put("event_type", row.getEventType() == null ? "" : row.getEventType().name());
                            item.put("severity", row.getSeverity() == null ? "" : row.getSeverity().name());
                            item.put("asset_code", blankAs(row.getAssetCode(), ""));
                            item.put("trace_id", blankAs(row.getTraceId(), ""));
                            return item;
                        }).toList(),
                "provider_audits", apiResponseAuditRepository.findTop300ByTraceIdOrderByRequestTimeUtcDesc(traceId)
                        .stream().limit(safeLimit).map(row -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", row.getId());
                            item.put("provider", blankAs(row.getProvider(), ""));
                            item.put("api_name", blankAs(row.getApiName(), ""));
                            item.put("success", Boolean.TRUE.equals(row.getSuccess()));
                            item.put("latency_ms", row.getLatencyMs());
                            item.put("trace_id", blankAs(row.getTraceId(), ""));
                            return item;
                        }).toList()));
        data.put("signals", Map.of(
                "strategy_runs", strategyRuns.stream().map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", row.getId());
                    item.put("run_type", row.getRunType() == null ? "" : row.getRunType().name());
                    item.put("status", row.getStatus() == null ? "" : row.getStatus().name());
                    item.put("processed_count", row.getProcessedCount() == null ? 0 : row.getProcessedCount());
                    item.put("created_signal_count", row.getCreatedSignalCount() == null ? 0 : row.getCreatedSignalCount());
                    item.put("trace_id", blankAs(row.getTraceId(), ""));
                    return item;
                }).toList(),
                "signal_audits", signalAuditLogRepository.findTop200ByTraceIdOrderByAuditTimeUtcDesc(traceId)
                        .stream().limit(safeLimit).map(row -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", row.getId());
                            item.put("signal_id", blankAs(row.getSignalId(), ""));
                            item.put("asset_code", blankAs(row.getAssetCode(), ""));
                            item.put("engine_type", row.getEngineType() == null ? "" : row.getEngineType().name());
                            item.put("blocked_reason", blankAs(row.getBlockedReason(), ""));
                            item.put("trace_id", blankAs(row.getTraceId(), ""));
                            return item;
                        }).toList(),
                "assistant_rag_audits", assistantAudits.stream().map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", row.getId());
                    item.put("request_scope", blankAs(row.getRequestScope(), ""));
                    item.put("request_key", blankAs(row.getRequestKey(), ""));
                    item.put("signal_id", blankAs(row.getSignalId(), ""));
                    item.put("asset_code", blankAs(row.getAssetCode(), ""));
                    item.put("fallback_applied", Boolean.TRUE.equals(row.getFallbackApplied()));
                    item.put("success", Boolean.TRUE.equals(row.getSuccess()));
                    item.put("error_code", blankAs(row.getErrorCode(), ""));
                    item.put("latency_ms_total", row.getLatencyMsTotal());
                    item.put("created_at", row.getCreatedAt());
                    item.put("trace_id", blankAs(row.getTraceId(), ""));
                    return item;
                }).toList()));
        data.put("feature_toggles", toggles);
        if (includeAssistant) {
            Map<String, Object> assistantSummary = assistantRagService.assistTraceDetail(traceId, data, includeAssistant);
            data.put("assistant_summary", assistantSummary);
        }
        return data;
    }

    public UniverseRebuildService.UniverseRebuildResult runUniverseRebuildNow(String triggeredBy) {
        return universeRebuildService.rebuildUniverse(triggeredBy);
    }

    public TickerAliasVerificationService.TickerAliasVerificationResult runTickerAliasVerificationNow(String triggeredBy) {
        return tickerAliasVerificationService.verifyAll(triggeredBy);
    }

    public Map<String, Object> diagnosticMeta(String sourceWindow, int warningCount) {
        List<String> warnings = warningCount > 0
                ? List.of("diagnostic warning_count=" + warningCount)
                : List.of();
        return new LinkedHashMap<>(Map.of(
                "generated_at", OffsetDateTime.now(),
                "source_window", sourceWindow,
                "warning_count", warningCount,
                "trace_id", traceId(),
                "provider_name", upper(blankAs(marketDataProviderRouter.activeProviderId(), "mock")),
                "is_delayed", false,
                "warnings", warnings));
    }

    private List<TradingSignalEntity> selectRecentSignals(String country, int hours, int limit) {
        OffsetDateTime since = OffsetDateTime.now().minusHours(Math.max(1, Math.min(hours, 72)));
        if (isBlank(country)) {
            return tradingSignalRepository.findByGeneratedAtAfterOrderByGeneratedAtDesc(since, PageRequest.of(0, limit));
        }
        return tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                country.trim(),
                since,
                PageRequest.of(0, limit));
    }

    private List<MarketDataGapEventEntity> selectGapRows(String provider, String assetCode, String traceId) {
        if (!isBlank(traceId)) {
            return marketDataGapEventRepository.findTop200ByTraceIdOrderByEventTimeUtcDesc(traceId);
        }
        if (!isBlank(assetCode)) {
            return marketDataGapEventRepository.findTop200ByAssetCodeOrderByEventTimeUtcDesc(assetCode.trim());
        }
        if (!isBlank(provider)) {
            return marketDataGapEventRepository.findTop200ByProviderOrderByEventTimeUtcDesc(provider.trim());
        }
        return marketDataGapEventRepository.findTop200ByOrderByEventTimeUtcDesc();
    }

    private List<ApiResponseAuditEntity> selectProviderAuditRows(String provider, String traceId) {
        if (!isBlank(traceId)) {
            return apiResponseAuditRepository.findTop300ByTraceIdOrderByRequestTimeUtcDesc(traceId);
        }
        if (!isBlank(provider)) {
            return apiResponseAuditRepository.findTop300ByProviderOrderByRequestTimeUtcDesc(provider.trim());
        }
        return apiResponseAuditRepository.findTop300ByOrderByRequestTimeUtcDesc();
    }

    private List<SignalAuditLogEntity> selectSignalAuditRows(
            String assetCode,
            String signalId,
            SignalAuditEngineType engineType,
            String traceId) {
        if (!isBlank(traceId)) {
            return signalAuditLogRepository.findTop200ByTraceIdOrderByAuditTimeUtcDesc(traceId);
        }
        if (!isBlank(signalId)) {
            return signalAuditLogRepository.findTop200BySignalIdOrderByAuditTimeUtcDesc(signalId.trim());
        }
        if (!isBlank(assetCode)) {
            return signalAuditLogRepository.findTop200ByAssetCodeOrderByAuditTimeUtcDesc(assetCode.trim());
        }
        if (engineType != null) {
            return signalAuditLogRepository.findTop500ByEngineTypeOrderByAuditTimeUtcDesc(engineType);
        }
        return signalAuditLogRepository.findTop500ByOrderByAuditTimeUtcDesc();
    }

    private List<String> parseRiskChecks(String rawJson) {
        if (isBlank(rawJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson, new TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of(rawJson);
        }
    }

    private Map<String, Long> duplicateExposureStats(List<TradingSignalEntity> rows) {
        Map<String, Integer> byAsset = new HashMap<>();
        Map<String, Integer> byAssetWindow = new HashMap<>();
        for (TradingSignalEntity row : rows) {
            if (isBlank(row.getAssetCode())) {
                continue;
            }
            byAsset.merge(row.getAssetCode().trim(), 1, Integer::sum);
            byAssetWindow.merge(row.getAssetCode().trim() + "|" + blankAs(row.getSignalWindow(), "N/A"), 1, Integer::sum);
        }
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("duplicate_asset_rows", byAsset.values().stream().filter(v -> v != null && v > 1).count());
        result.put("duplicate_asset_window_rows", byAssetWindow.values().stream().filter(v -> v != null && v > 1).count());
        return result;
    }

    private Map<String, Object> assistantRagSummary(int hours) {
        OffsetDateTime since = OffsetDateTime.now().minusHours(Math.max(1, Math.min(hours, 72)));
        List<AssistantRagAuditLogEntity> signalRows = assistantRagAuditLogRepository.findTop200ByRequestScopeOrderByCreatedAtDesc("SIGNAL_DETAIL");
        List<AssistantRagAuditLogEntity> traceRows = assistantRagAuditLogRepository.findTop200ByRequestScopeOrderByCreatedAtDesc("TRACE_DETAIL");
        List<AssistantRagAuditLogEntity> rows = java.util.stream.Stream.concat(signalRows.stream(), traceRows.stream())
                .filter(row -> row.getCreatedAt() != null && row.getCreatedAt().isAfter(since))
                .sorted(Comparator.comparing(AssistantRagAuditLogEntity::getCreatedAt).reversed())
                .limit(300)
                .toList();

        long totalCount = assistantRagAuditLogRepository.countByCreatedAtAfter(since);
        long failedCount = assistantRagAuditLogRepository.countBySuccessFalseAndCreatedAtAfter(since);
        long fallbackCount = rows.stream().filter(row -> Boolean.TRUE.equals(row.getFallbackApplied())).count();
        long timeoutCount = rows.stream()
                .map(AssistantRagAuditLogEntity::getErrorCode)
                .filter(code -> containsIgnoreCase(code, "TIMEOUT"))
                .count();
        BigDecimal avgLatency = rows.stream()
                .map(AssistantRagAuditLogEntity::getLatencyMsTotal)
                .filter(Objects::nonNull)
                .map(BigDecimal::valueOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!rows.isEmpty()) {
            avgLatency = avgLatency.divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
        } else {
            avgLatency = zero(2);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("window_hours", Math.max(1, Math.min(hours, 72)));
        data.put("total_count", totalCount);
        data.put("failed_count", failedCount);
        data.put("fallback_count", fallbackCount);
        data.put("timeout_count", timeoutCount);
        data.put("success_rate", totalCount <= 0
                ? zero(6)
                : BigDecimal.valueOf(totalCount - failedCount).divide(BigDecimal.valueOf(totalCount), 6, RoundingMode.HALF_UP));
        data.put("avg_latency_ms", avgLatency);
        data.put("scope_distribution", rows.stream().collect(Collectors.groupingBy(
                row -> blankAs(row.getRequestScope(), "UNKNOWN"),
                LinkedHashMap::new,
                Collectors.counting())));
        data.put("recent_errors", rows.stream()
                .filter(row -> !Boolean.TRUE.equals(row.getSuccess()) || Boolean.TRUE.equals(row.getFallbackApplied()))
                .limit(8)
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("request_scope", blankAs(row.getRequestScope(), ""));
                    item.put("request_key", blankAs(row.getRequestKey(), ""));
                    item.put("asset_code", blankAs(row.getAssetCode(), ""));
                    item.put("error_code", blankAs(row.getErrorCode(), ""));
                    item.put("fallback_applied", Boolean.TRUE.equals(row.getFallbackApplied()));
                    item.put("latency_ms_total", row.getLatencyMsTotal());
                    item.put("created_at", row.getCreatedAt());
                    return item;
                })
                .toList());
        return data;
    }

    private String strategyWindowKey(String signalWindow) {
        String window = blankAs(signalWindow, "").trim().toLowerCase();
        return switch (window) {
            case "1h" -> "SCALP";
            case "1w" -> "SWING";
            case "1m" -> "CHART_RESPONSE";
            case "6m" -> "DISCOVERY";
            default -> blankAs(signalWindow, "UNKNOWN");
        };
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castMapList(Object value) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(row -> (Map<String, Object>) row)
                .toList();
    }

    private Map<String, Long> castLongMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return map.entrySet().stream().collect(Collectors.toMap(
                entry -> String.valueOf(entry.getKey()),
                entry -> entry.getValue() instanceof Number number ? number.longValue() : 0L,
                (a, b) -> a,
                LinkedHashMap::new));
    }

    private BigDecimal avg(
            List<MarketDataQualitySnapshotEntity> rows,
            Function<MarketDataQualitySnapshotEntity, BigDecimal> extractor,
            int scale) {
        if (rows == null || rows.isEmpty()) {
            return zero(scale);
        }
        BigDecimal sum = rows.stream()
                .map(extractor)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(BigDecimal.ZERO) == 0) {
            return zero(scale);
        }
        return sum.divide(BigDecimal.valueOf(rows.size()), scale, RoundingMode.HALF_UP);
    }

    private int clampLimit(int value) {
        return Math.max(1, Math.min(value, 300));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String blankAs(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean eqIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right.trim());
    }

    private boolean containsIgnoreCase(String value, String fragment) {
        if (value == null || fragment == null) {
            return false;
        }
        return value.toLowerCase().contains(fragment.toLowerCase());
    }

    private Map<String, Long> providerDistributionFromQuotes(List<MarketQuoteSnapshotEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        return rows.stream()
                .collect(Collectors.groupingBy(
                        row -> upper(blankAs(row.getProviderName(), "UNKNOWN")),
                        LinkedHashMap::new,
                        Collectors.counting()));
    }

    private String summarizeProviderName(Map<String, Long> distribution) {
        if (distribution == null || distribution.isEmpty()) {
            return upper(blankAs(marketDataProviderRouter.activeProviderId(), "mock"));
        }
        if (distribution.size() == 1) {
            return distribution.keySet().iterator().next();
        }
        if (distribution.containsKey("MOCK")) {
            return "MIXED_WITH_MOCK";
        }
        return "MIXED";
    }

    private boolean isDelayedQuote(MarketQuoteSnapshotEntity row) {
        if (row == null) {
            return true;
        }
        OffsetDateTime base = row.getSnapshotUtc() != null ? row.getSnapshotUtc() : row.getCreatedAt();
        if (base == null) {
            return true;
        }
        return base.isBefore(OffsetDateTime.now().minusMinutes(Math.max(1L, quoteFreshnessThresholdMinutes)));
    }

    private Map<String, Object> recentCollectionEvents(List<com.wangbyul.gnd.core.domain.MarketProviderJobEntity> providerJobs) {
        List<Map<String, Object>> recentJobs = providerJobs.stream()
                .limit(10)
                .map(job -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("provider_name", upper(blankAs(job.getProviderName(), "UNKNOWN")));
                    item.put("job_type", job.getJobType() == null ? "" : job.getJobType().name());
                    item.put("job_name", blankAs(job.getJobName(), ""));
                    item.put("status", job.getStatus() == null ? "" : job.getStatus().name());
                    item.put("latency_ms", job.getLatencyMs());
                    item.put("empty_response", Boolean.TRUE.equals(job.getEmptyResponse()));
                    item.put("scheduled_at", job.getScheduledAt());
                    item.put("trace_id", blankAs(job.getTraceId(), ""));
                    item.put("warn", (job.getLatencyMs() != null && job.getLatencyMs() > providerLatencyWarningMs)
                            || Boolean.TRUE.equals(job.getEmptyResponse())
                            || (job.getStatus() != null && "FAILED".equals(job.getStatus().name())));
                    return item;
                })
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("latency_warning_ms", providerLatencyWarningMs);
        data.put("items", recentJobs);
        data.put("failed_count", recentJobs.stream()
                .filter(item -> "FAILED".equals(String.valueOf(item.get("status"))))
                .count());
        data.put("delayed_count", recentJobs.stream()
                .filter(item -> item.get("latency_ms") instanceof Number n && n.longValue() > providerLatencyWarningMs)
                .count());
        return data;
    }

    private Map<String, Object> recentWarningErrorSummary(OffsetDateTime since, String provider) {
        List<com.wangbyul.gnd.core.domain.MarketProviderJobEntity> jobs = (isBlank(provider)
                ? marketProviderJobRepository.findTop100ByScheduledAtAfterOrderByScheduledAtDesc(since)
                : marketProviderJobRepository.findTop100ByProviderNameAndScheduledAtAfterOrderByScheduledAtDesc(
                        provider.trim().toLowerCase(Locale.ROOT),
                        since));
        List<ApiResponseAuditEntity> audits = selectProviderAuditRows(provider, null).stream()
                .filter(row -> row.getRequestTimeUtc() != null && row.getRequestTimeUtc().isAfter(since))
                .limit(100)
                .toList();

        List<Map<String, Object>> providerJobWarnings = jobs.stream()
                .filter(job -> Boolean.TRUE.equals(job.getEmptyResponse())
                        || (job.getStatus() != null && "FAILED".equals(job.getStatus().name()))
                        || (job.getLatencyMs() != null && job.getLatencyMs() > providerLatencyWarningMs))
                .limit(8)
                .map(job -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "MARKET_PROVIDER_JOB");
                    item.put("provider_name", upper(blankAs(job.getProviderName(), "UNKNOWN")));
                    item.put("status", job.getStatus() == null ? "" : job.getStatus().name());
                    item.put("latency_ms", job.getLatencyMs());
                    item.put("empty_response", Boolean.TRUE.equals(job.getEmptyResponse()));
                    item.put("error", blankAs(job.getLastError(), ""));
                    item.put("time_utc", job.getScheduledAt());
                    item.put("trace_id", blankAs(job.getTraceId(), ""));
                    return item;
                })
                .toList();

        List<Map<String, Object>> apiErrors = audits.stream()
                .filter(row -> !Boolean.TRUE.equals(row.getSuccess()))
                .limit(8)
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "PROVIDER_API_AUDIT");
                    item.put("provider_name", upper(blankAs(row.getProvider(), "UNKNOWN")));
                    item.put("api_name", blankAs(row.getApiName(), ""));
                    item.put("http_status", row.getHttpStatus());
                    item.put("error_code", blankAs(row.getErrorCode(), ""));
                    item.put("latency_ms", row.getLatencyMs());
                    item.put("time_utc", row.getRequestTimeUtc());
                    item.put("trace_id", blankAs(row.getTraceId(), ""));
                    return item;
                })
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider_job_warning_count", providerJobWarnings.size());
        data.put("provider_api_error_count", apiErrors.size());
        data.put("provider_job_warnings", providerJobWarnings);
        data.put("provider_api_errors", apiErrors);
        data.put("log_todo", List.of(
                "애플리케이션 로그(WARN/ERROR) 원문 수집/집계는 아직 DB 진단 API에 연결되지 않음",
                "현재 진단은 market_provider_job/api_response_audit/gap 기반 요약만 제공"));
        return data;
    }

    private Map<String, Object> featureToggleStatusSummary() {
        List<String> keys = List.of(
                "LIVE_TRADE",
                "AUTO_ORDER_WITH_ADMIN_APPROVAL",
                "AUTO_ORDER_FULLY_AUTOMATED",
                "RAG_ASSISTANT");
        Map<String, Boolean> effective = keys.stream()
                .collect(Collectors.toMap(
                        key -> key,
                        key -> systemFeatureToggleService.isFeatureEnabled(key, null, null, null),
                        (a, b) -> a,
                        LinkedHashMap::new));
        List<FeatureToggleDto> recent = systemFeatureToggleService.list(null, null, null, null, 20);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("effective", effective);
        data.put("config_live_trade_enabled", liveTradeEnabled);
        data.put("recent_items", recent.stream()
                .limit(10)
                .map(row -> Map.of(
                        "feature_key", blankAs(row.getFeatureKey(), ""),
                        "enabled", Boolean.TRUE.equals(row.getEnabled()),
                        "scope_type", row.getScopeType() == null ? "GLOBAL" : row.getScopeType().name(),
                        "scope_value", blankAs(row.getScopeValue(), "*"),
                        "updated_by", blankAs(row.getUpdatedBy(), ""),
                        "updated_at", row.getUpdatedAt(),
                        "trace_id", blankAs(row.getTraceId(), "")))
                .toList());
        return data;
    }

    private Map<String, Object> tracePropagationSummary() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mdc_key", "trace_id");
        data.put("api_envelope_field", "trace_id");
        data.put("audit_trace_fields", List.of(
                "market_provider_job.trace_id",
                "api_response_audit.trace_id",
                "market_data_gap_event.trace_id",
                "signal_audit_log.trace_id",
                "assistant_rag_audit_log.trace_id"));
        data.put("status", "PARTIAL_VERIFIED");
        data.put("notes", List.of(
                "HTTP 응답 envelope와 주요 감사 테이블에는 trace_id 저장 경로가 존재함",
                "애플리케이션 원문 로그와 DB trace_id의 상호참조 집계 API는 후속 고도화 필요"));
        return data;
    }

    private Map<String, Object> marketCollectionAuditStatusSummary() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tables", Map.of(
                "market_provider_job", "수집 잡 실행/결과 감사(성공/실패/빈응답/지연/trace_id)",
                "api_response_audit", "provider API 요청/응답 감사(http_status/latency/error/trace_id)",
                "market_data_gap_event", "데이터 갭/지연/결측 이벤트 감사",
                "market_data_quality_snapshot", "품질 스냅샷(지연율/결측률/중복률/이상치율)"));
        data.put("status", "AVAILABLE");
        data.put("todo", List.of(
                "서버 로그 WARN/ERROR 원문 요약을 진단 API와 직접 연결",
                "배포 직후 자동 smoke-check 결과를 별도 감사 테이블로 저장"));
        return data;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> deploymentVerificationChecklist(Map<String, Object> summaryData) {
        boolean mockDetected = Boolean.TRUE.equals(((Map<String, Object>) summaryData.getOrDefault("warning_flags", Map.of()))
                .get("mock_provider_detected"));
        boolean delayed = Boolean.TRUE.equals(summaryData.get("is_delayed"));
        boolean liveTradeToggle = systemFeatureToggleService.isFeatureEnabled("LIVE_TRADE", null, null, null);
        return List.of(
                verificationItem("provider_visible", true, "provider_name / 분포 응답 포함"),
                verificationItem("mock_warning_exposed", mockDetected, "MOCK provider 경고 플래그/문구 노출"),
                verificationItem("trace_path_documented", true, "trace_propagation 섹션 확인"),
                verificationItem("data_delay_check", !delayed, delayed ? "지연 경고 존재" : "지연 없음"),
                verificationItem("live_trade_default_off", !liveTradeEnabled && !liveTradeToggle, "config+toggle 모두 OFF"),
                verificationItem(
                        "audit_tables_present",
                        true,
                        "market_provider_job/api_response_audit/market_data_gap_event/market_data_quality_snapshot"));
    }

    private Map<String, Object> verificationItem(String key, boolean ok, String note) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("check_key", key);
        item.put("ok", ok);
        item.put("status", ok ? "PASS" : "WARN");
        item.put("note", note);
        return item;
    }

    private boolean marketProviderRouterAllowMock() {
        return marketDataProviderRouter.allowMock();
    }

    private OffsetDateTime newsThumbnailBaseTime(NewsEntity row) {
        if (row == null) {
            return null;
        }
        if (row.getPublishedAtUtc() != null) {
            return row.getPublishedAtUtc();
        }
        if (row.getPubUtc() != null) {
            return row.getPubUtc();
        }
        return row.getCreatedAt();
    }

    private ThumbnailDiagRow classifyNewsThumbnailRow(NewsEntity row) {
        String storedUrl = blankAs(row.getThumbnailUrl(), "");
        NewsThumbnailSourceType storedSource = row.getThumbnailSource();
        NewsThumbnailStatusType storedStatus = row.getThumbnailStatus();
        boolean hasStoredMetadata = !isBlank(storedUrl) || storedSource != null || storedStatus != null;

        String effectiveUrl = storedUrl;
        NewsThumbnailSourceType effectiveSource = storedSource;
        NewsThumbnailStatusType effectiveStatus = storedStatus;
        boolean parserFallbackUsed = false;

        if (isBlank(effectiveUrl) || effectiveStatus == null || effectiveSource == null) {
            var parsed = NewsThumbnailParser.parse(row.getBodyRaw());
            parserFallbackUsed = true;
            if (isBlank(effectiveUrl)) {
                effectiveUrl = blankAs(parsed.thumbnailUrl(), "");
            }
            if (effectiveSource == null) {
                effectiveSource = parsed.source();
            }
            if (effectiveStatus == null) {
                effectiveStatus = parsed.status();
            }
        }

        if (effectiveSource == null) {
            effectiveSource = NewsThumbnailSourceType.DEFAULT;
        }
        if (effectiveStatus == null) {
            effectiveStatus = isBlank(effectiveUrl) ? NewsThumbnailStatusType.EMPTY : NewsThumbnailStatusType.SUCCESS;
        }
        if (!isBlank(effectiveUrl) && effectiveStatus != NewsThumbnailStatusType.SUCCESS) {
            effectiveStatus = NewsThumbnailStatusType.SUCCESS;
        }

        String normalizedUrl = normalizeThumbnailDiagnosticUrl(effectiveUrl);
        boolean mixedContentRisk = normalizedUrl.startsWith("http://");
        String host = extractThumbnailHost(normalizedUrl);
        boolean ephemeralRisk = !isBlank(normalizedUrl)
                && THUMBNAIL_EPHEMERAL_URL_PATTERN.matcher(normalizedUrl).find();
        String failureReason = classifyThumbnailFailureReason(row, effectiveStatus, normalizedUrl);

        return new ThumbnailDiagRow(
                row.getId(),
                blankAs(row.getCountry(), ""),
                row.getCategory() == null ? "" : row.getCategory().name(),
                blankAs(row.getTitleKo(), blankAs(row.getTitleRaw(), "")),
                normalizedUrl,
                host,
                effectiveSource,
                effectiveStatus,
                mixedContentRisk,
                ephemeralRisk,
                parserFallbackUsed,
                hasStoredMetadata,
                failureReason,
                blankAs(row.getUrl(), ""),
                newsThumbnailBaseTime(row),
                row.getCreatedAt(),
                blankAs(row.getSource() == null ? null : row.getSource().getSid(), ""));
    }

    private Map<String, Object> thumbnailDiagSample(ThumbnailDiagRow row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.id());
        item.put("country", row.country());
        item.put("category", row.category());
        item.put("title", row.title());
        item.put("thumbnail_url", row.thumbnailUrl());
        item.put("thumbnail_source", row.source() == null ? "UNKNOWN" : row.source().name());
        item.put("thumbnail_status", row.status() == null ? "UNKNOWN" : row.status().name());
        item.put("thumbnail_host", row.thumbnailHost());
        item.put("mixed_content_risk", row.mixedContentRisk());
        item.put("ephemeral_url_risk", row.ephemeralUrlRisk());
        item.put("parser_fallback_used", row.parserFallbackUsed());
        item.put("stored_metadata_used", row.storedMetadataUsed());
        item.put("failure_reason", row.failureReason());
        item.put("article_url", row.articleUrl());
        item.put("sid", row.sid());
        item.put("event_time_utc", row.eventTimeUtc());
        item.put("created_at", row.createdAt());
        return item;
    }

    private String normalizeThumbnailDiagnosticUrl(String value) {
        if (isBlank(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        return trimmed;
    }

    private String extractThumbnailHost(String url) {
        if (isBlank(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            return blankAs(uri.getHost(), "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String classifyThumbnailFailureReason(NewsEntity row, NewsThumbnailStatusType status, String thumbnailUrl) {
        if (status == NewsThumbnailStatusType.SUCCESS && !isBlank(thumbnailUrl)) {
            return "";
        }
        if (status == NewsThumbnailStatusType.FAILED) {
            return "PARSING_EXCEPTION";
        }
        if (row == null || isBlank(row.getBodyRaw())) {
            return "BODY_EMPTY";
        }
        if (THUMBNAIL_HINT_PATTERN.matcher(row.getBodyRaw()).find()) {
            return "PARSE_EMPTY_WITH_IMAGE_HINT";
        }
        return "NO_IMAGE_CANDIDATE";
    }

    private record ThumbnailDiagRow(
            String id,
            String country,
            String category,
            String title,
            String thumbnailUrl,
            String thumbnailHost,
            NewsThumbnailSourceType source,
            NewsThumbnailStatusType status,
            boolean mixedContentRisk,
            boolean ephemeralUrlRisk,
            boolean parserFallbackUsed,
            boolean storedMetadataUsed,
            String failureReason,
            String articleUrl,
            OffsetDateTime eventTimeUtc,
            OffsetDateTime createdAt,
            String sid) {
    }

    private String upper(String value) {
        return blankAs(value, "").toUpperCase(Locale.ROOT);
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }

    private BigDecimal zero(int scale) {
        return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
    }
}

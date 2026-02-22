package com.wangbyul.gnd.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.dto.FeatureToggleDto;
import com.wangbyul.gnd.api.service.assistant.AssistantRagService;
import com.wangbyul.gnd.core.domain.ApiResponseAuditEntity;
import com.wangbyul.gnd.core.domain.AssistantRagAuditLogEntity;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.AuditSeverityType;
import com.wangbyul.gnd.core.domain.MarketDataGapEventEntity;
import com.wangbyul.gnd.core.domain.MarketDataQualitySnapshotEntity;
import com.wangbyul.gnd.core.domain.SignalAuditEngineType;
import com.wangbyul.gnd.core.domain.SignalAuditLogEntity;
import com.wangbyul.gnd.core.domain.StrategyRunEntity;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.repository.ApiResponseAuditRepository;
import com.wangbyul.gnd.core.repository.AssistantRagAuditLogRepository;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketDataGapEventRepository;
import com.wangbyul.gnd.core.repository.MarketDataQualitySnapshotRepository;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketProviderJobRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.SignalAuditLogRepository;
import com.wangbyul.gnd.core.repository.StrategyRunRepository;
import com.wangbyul.gnd.core.repository.TradingSignalRepository;
import com.wangbyul.gnd.core.service.MappingQualityReportService;
import com.wangbyul.gnd.core.service.TickerAliasVerificationService;
import com.wangbyul.gnd.core.service.UniverseRebuildService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
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

    private final UniverseRebuildService universeRebuildService;
    private final TickerAliasVerificationService tickerAliasVerificationService;
    private final MappingQualityReportService mappingQualityReportService;
    private final MarketDataQualitySnapshotRepository marketDataQualitySnapshotRepository;
    private final MarketDataGapEventRepository marketDataGapEventRepository;
    private final ApiResponseAuditRepository apiResponseAuditRepository;
    private final MarketProviderJobRepository marketProviderJobRepository;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final MarketPriceBarRepository marketPriceBarRepository;
    private final SignalAuditLogRepository signalAuditLogRepository;
    private final TradingSignalRepository tradingSignalRepository;
    private final StrategyRunRepository strategyRunRepository;
    private final AssetUniverseRepository assetUniverseRepository;
    private final SystemFeatureToggleService systemFeatureToggleService;
    private final AssistantRagService assistantRagService;
    private final AssistantRagAuditLogRepository assistantRagAuditLogRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.market.quality.minimum-quality-score:70}")
    private BigDecimal minimumQualityScore;

    @Value("${app.signal.minimum-combined-confidence:0.45}")
    private BigDecimal minimumCombinedConfidence;

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

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope_country", blankAs(country, "ALL"));
        data.put("scope_provider", blankAs(provider, "ALL"));
        data.put("window_hours", Math.max(1, Math.min(hours, 72)));
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
        return Map.of(
                "generated_at", OffsetDateTime.now(),
                "source_window", sourceWindow,
                "warning_count", warningCount);
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

    private BigDecimal zero(int scale) {
        return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
    }
}

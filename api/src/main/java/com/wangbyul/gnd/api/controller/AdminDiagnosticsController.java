package com.wangbyul.gnd.api.controller;

import com.wangbyul.gnd.api.service.AdminDiagnosticsService;
import com.wangbyul.gnd.core.domain.AuditSeverityType;
import com.wangbyul.gnd.core.domain.SignalAuditEngineType;
import com.wangbyul.gnd.core.dto.ApiEnvelope;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 진단 API 컨트롤러.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@RestController
@RequestMapping("/api/admin/diagnostics")
public class AdminDiagnosticsController {

    private final AdminDiagnosticsService adminDiagnosticsService;

    public AdminDiagnosticsController(AdminDiagnosticsService adminDiagnosticsService) {
        this.adminDiagnosticsService = adminDiagnosticsService;
    }

    @GetMapping("/market-collection/summary")
    public ApiEnvelope<Map<String, Object>> marketCollectionSummary(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String provider,
            @RequestParam(defaultValue = "24") int hours) {
        Map<String, Object> data = adminDiagnosticsService.getMarketCollectionSummary(country, provider, hours);
        int warningCount = 0;
        if (decimalValue(data.get("avg_quality_score")).compareTo(BigDecimal.valueOf(70d)) < 0) {
            warningCount++;
        }
        if (longValue(data.get("unresolved_gap_count")) > 0) {
            warningCount++;
        }
        return envelope(data, adminDiagnosticsService.diagnosticMeta("market-collection:summary", warningCount));
    }

    @GetMapping("/market-collection/gaps")
    public ApiEnvelope<Map<String, Object>> marketCollectionGaps(
            @RequestParam(required = false) String provider,
            @RequestParam(name = "asset_code", required = false) String assetCode,
            @RequestParam(required = false) AuditSeverityType severity,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(name = "trace_id", required = false) String traceId,
            @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> data = adminDiagnosticsService.getMarketCollectionGaps(
                provider,
                assetCode,
                severity,
                resolved,
                traceId,
                limit);
        int warningCount = intValue(data.get("unresolved_error_count"));
        return envelope(data, adminDiagnosticsService.diagnosticMeta("market-collection:gaps", warningCount));
    }

    @GetMapping("/market-collection/provider-audit")
    public ApiEnvelope<Map<String, Object>> providerAudit(
            @RequestParam(required = false) String provider,
            @RequestParam(name = "api_name", required = false) String apiName,
            @RequestParam(required = false) Boolean success,
            @RequestParam(name = "trace_id", required = false) String traceId,
            @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> data = adminDiagnosticsService.getMarketCollectionProviderAudit(
                provider,
                apiName,
                success,
                traceId,
                limit);
        int warningCount = intValue(data.get("failed_count"));
        return envelope(data, adminDiagnosticsService.diagnosticMeta("market-collection:provider-audit", warningCount));
    }

    @GetMapping("/market-collection/quality-score")
    public ApiEnvelope<Map<String, Object>> qualityScore(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String theme,
            @RequestParam(defaultValue = "7") int days) {
        Map<String, Object> data = adminDiagnosticsService.getMarketCollectionQualityScore(country, theme, days);
        int warningCount = intValue(data.get("below_threshold_count"));
        return envelope(data, adminDiagnosticsService.diagnosticMeta("market-collection:quality-score", warningCount));
    }

    @GetMapping("/universe")
    public ApiEnvelope<Map<String, Object>> universeDiagnostics(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String theme,
            @RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> data = adminDiagnosticsService.getUniverseDiagnostics(country, theme, Math.max(1, Math.min(limit, 200)));
        int warningCount = estimateFamilyWarning(data);
        return envelope(data, adminDiagnosticsService.diagnosticMeta("universe:latest", warningCount));
    }

    @GetMapping("/universe/diversity")
    public ApiEnvelope<Map<String, Object>> universeDiversity(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String theme,
            @RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> data = adminDiagnosticsService.getUniverseDiversityDiagnostics(country, theme, limit);
        int warningCount = Boolean.TRUE.equals(data.get("diversity_warning")) ? 1 : 0;
        return envelope(data, adminDiagnosticsService.diagnosticMeta("universe:diversity", warningCount));
    }

    @PostMapping("/universe/rebuild")
    public ApiEnvelope<Map<String, Object>> rebuildUniverseNow(
            @RequestParam(defaultValue = "admin-api") String triggeredBy) {
        var result = adminDiagnosticsService.runUniverseRebuildNow(triggeredBy);
        Map<String, Object> data = Map.of(
                "requested", true,
                "total_assets", result.totalAssets(),
                "core_assets", result.coreAssets(),
                "country_minimum_fallback_count", result.countryMinimumFallbackCount(),
                "theme_minimum_fallback_count", result.themeMinimumFallbackCount(),
                "triggered_by", result.triggeredBy(),
                "generated_at", result.generatedAt());
        return envelope(data, adminDiagnosticsService.diagnosticMeta("universe:rebuild-now", 0));
    }

    @GetMapping("/ticker-alias")
    public ApiEnvelope<Map<String, Object>> tickerAliasDiagnostics(
            @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> data = adminDiagnosticsService.getTickerAliasDiagnostics(Math.max(1, Math.min(limit, 300)));
        int warningCount = intValue(data.get("failed_rows"));
        return envelope(data, adminDiagnosticsService.diagnosticMeta("ticker-alias:latest", warningCount));
    }

    @PostMapping("/ticker-alias/verify")
    public ApiEnvelope<Map<String, Object>> verifyTickerAliasNow(
            @RequestParam(defaultValue = "admin-api") String triggeredBy) {
        var result = adminDiagnosticsService.runTickerAliasVerificationNow(triggeredBy);
        Map<String, Object> data = Map.of(
                "requested", true,
                "alias_count", result.aliasCount(),
                "passed_checks", result.passedChecks(),
                "failed_checks", result.failedChecks(),
                "error_count", result.errorCount(),
                "triggered_by", result.triggeredBy(),
                "generated_at", result.generatedAt());
        return envelope(data, adminDiagnosticsService.diagnosticMeta("ticker-alias:verify-now", result.errorCount()));
    }

    @GetMapping("/news-asset-mapping")
    public ApiEnvelope<Map<String, Object>> newsAssetMappingDiagnostics(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String theme,
            @RequestParam(defaultValue = "false") boolean refresh) {
        Map<String, Object> data = adminDiagnosticsService.getNewsAssetMappingDiagnostics(country, theme, refresh);
        int warningCount = estimateMappingWarningCount(data);
        return envelope(data, adminDiagnosticsService.diagnosticMeta("news-asset-mapping:7d", warningCount));
    }

    @GetMapping("/signals/audit")
    public ApiEnvelope<Map<String, Object>> signalAuditDiagnostics(
            @RequestParam(required = false) String country,
            @RequestParam(name = "asset_code", required = false) String assetCode,
            @RequestParam(name = "signal_id", required = false) String signalId,
            @RequestParam(name = "engine_type", required = false) SignalAuditEngineType engineType,
            @RequestParam(name = "trace_id", required = false) String traceId,
            @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> data = adminDiagnosticsService.getSignalAuditDiagnostics(
                country,
                assetCode,
                signalId,
                engineType,
                traceId,
                limit);
        int warningCount = intValue(data.get("blocked_count"));
        return envelope(data, adminDiagnosticsService.diagnosticMeta("signals:audit", warningCount));
    }

    @GetMapping("/signals/alignment")
    public ApiEnvelope<Map<String, Object>> signalAlignmentDiagnostics(
            @RequestParam(required = false) String country,
            @RequestParam(defaultValue = "24") int hours) {
        Map<String, Object> data = adminDiagnosticsService.getSignalAlignmentDiagnostics(country, hours);
        int warningCount = intValue(data.get("future_data_blocked_count"))
                + intValue(data.get("invalid_publish_fetch_count"))
                + intValue(data.get("translation_delayed_count"));
        return envelope(data, adminDiagnosticsService.diagnosticMeta("signals:alignment", warningCount));
    }

    @GetMapping("/signals/confidence-distribution")
    public ApiEnvelope<Map<String, Object>> signalConfidenceDiagnostics(
            @RequestParam(required = false) String country,
            @RequestParam(defaultValue = "24") int hours) {
        Map<String, Object> data = adminDiagnosticsService.getSignalConfidenceDistribution(country, hours);
        int warningCount = intValue(data.get("low_confidence_count"));
        return envelope(data, adminDiagnosticsService.diagnosticMeta("signals:confidence-distribution", warningCount));
    }

    @GetMapping("/trace-detail")
    public ApiEnvelope<Map<String, Object>> traceDetail(
            @RequestParam(name = "trace_id") String traceId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(name = "assistant", defaultValue = "true") boolean assistant) {
        Map<String, Object> data = adminDiagnosticsService.getTraceDetail(traceId, limit, assistant);
        int warningCount = estimateTraceWarningCount(data);
        return envelope(data, adminDiagnosticsService.diagnosticMeta("trace-detail", warningCount));
    }

    private int estimateFamilyWarning(Map<String, Object> data) {
        Object families = data.get("top_repeated_families");
        if (families instanceof Map<?, ?> map) {
            long heavy = map.values().stream()
                    .filter(Number.class::isInstance)
                    .map(Number.class::cast)
                    .filter(value -> value.longValue() > 3L)
                    .count();
            return (int) heavy;
        }
        return 0;
    }

    private int estimateMappingWarningCount(Map<String, Object> data) {
        Object latest = data.get("latest");
        if (!(latest instanceof Map<?, ?> latestMap)) {
            return 0;
        }
        double falsePositive = doubleValue(latestMap.get("theme_match_false_positive_rate"));
        double overExpansion = doubleValue(latestMap.get("country_theme_overexpansion_rate"));
        int warning = 0;
        if (falsePositive > 0.35d) {
            warning++;
        }
        if (overExpansion > 0.30d) {
            warning++;
        }
        return warning;
    }

    private int estimateTraceWarningCount(Map<String, Object> data) {
        Object counts = data.get("counts");
        if (!(counts instanceof Map<?, ?> countMap)) {
            return 0;
        }
        return intValue(countMap.get("gap_events")) + intValue(countMap.get("signal_audits"));
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0d;
    }

    private <T> ApiEnvelope<T> envelope(T data, Map<String, Object> meta) {
        return ApiEnvelope.<T>builder()
                .data(data)
                .meta(meta)
                .traceId(traceId())
                .build();
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }
}

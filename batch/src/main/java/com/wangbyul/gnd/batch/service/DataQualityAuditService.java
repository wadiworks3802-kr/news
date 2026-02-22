package com.wangbyul.gnd.batch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.ApiResponseAuditEntity;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.AuditSeverityType;
import com.wangbyul.gnd.core.domain.MarketDataGapEventEntity;
import com.wangbyul.gnd.core.domain.MarketDataQualitySnapshotEntity;
import com.wangbyul.gnd.core.domain.MarketGapEventType;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
import com.wangbyul.gnd.core.repository.ApiResponseAuditRepository;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketDataGapEventRepository;
import com.wangbyul.gnd.core.repository.MarketDataQualitySnapshotRepository;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.util.SensitiveDataMaskingUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시장데이터 수집 품질 감사 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 1차 구현에서는 품질지표 계산/갭 이벤트 적재/응답 감사 정리의 골격을 제공한다.
 */
@Slf4j
@Service
public class DataQualityAuditService {

    private static final String DEFAULT_PROVIDER = "MOCK";

    private final AssetUniverseRepository assetUniverseRepository;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final MarketPriceBarRepository marketPriceBarRepository;
    private final MarketDataQualitySnapshotRepository marketDataQualitySnapshotRepository;
    private final MarketDataGapEventRepository marketDataGapEventRepository;
    private final ApiResponseAuditRepository apiResponseAuditRepository;
    private final ObjectMapper objectMapper;
    private final SensitiveDataMaskingUtil sensitiveDataMaskingUtil;

    @Value("${app.market.quality.max-quote-delay-seconds:120}")
    private long maxQuoteDelaySeconds;

    @Value("${app.market.quality.max-bar-delay-seconds:300}")
    private long maxBarDelaySeconds;

    @Value("${app.market.quality.outlier-threshold-pct:20}")
    private BigDecimal outlierThresholdPct;

    @Value("${app.market.quality.audit-sample-rate:0.1}")
    private BigDecimal auditSampleRate;

    @Value("${app.market.quality.minimum-quality-score:70}")
    private BigDecimal minimumQualityScore;

    @Value("${app.batch.api-response-audit-retention-days:14}")
    private int apiResponseAuditRetentionDays;

    public DataQualityAuditService(
            AssetUniverseRepository assetUniverseRepository,
            MarketQuoteSnapshotRepository marketQuoteSnapshotRepository,
            MarketPriceBarRepository marketPriceBarRepository,
            MarketDataQualitySnapshotRepository marketDataQualitySnapshotRepository,
            MarketDataGapEventRepository marketDataGapEventRepository,
            ApiResponseAuditRepository apiResponseAuditRepository,
            ObjectMapper objectMapper,
            SensitiveDataMaskingUtil sensitiveDataMaskingUtil) {
        this.assetUniverseRepository = assetUniverseRepository;
        this.marketQuoteSnapshotRepository = marketQuoteSnapshotRepository;
        this.marketPriceBarRepository = marketPriceBarRepository;
        this.marketDataQualitySnapshotRepository = marketDataQualitySnapshotRepository;
        this.marketDataGapEventRepository = marketDataGapEventRepository;
        this.apiResponseAuditRepository = apiResponseAuditRepository;
        this.objectMapper = objectMapper;
        this.sensitiveDataMaskingUtil = sensitiveDataMaskingUtil;
    }

    /**
     * 품질 스냅샷 집계를 생성한다.
     */
    @Transactional
    public void runMarketDataQualityAudit() {
        List<AssetUniverseEntity> assets = assetUniverseRepository.findByActiveTrueOrderByUpdatedAtDesc();
        if (assets.isEmpty()) {
            return;
        }

        Map<String, List<AssetUniverseEntity>> grouped = assets.stream()
                .collect(java.util.stream.Collectors.groupingBy(asset -> groupKey(asset.getCountry(), asset.getTheme())));

        OffsetDateTime now = OffsetDateTime.now();
        for (Map.Entry<String, List<AssetUniverseEntity>> entry : grouped.entrySet()) {
            String[] scope = splitGroup(entry.getKey());
            List<AssetUniverseEntity> scopedAssets = entry.getValue();
            int expected = scopedAssets.size();
            int quoteCollected = 0;
            int barCollected = 0;
            int delayedQuote = 0;
            int delayedBar = 0;
            int anomalyCount = 0;

            for (AssetUniverseEntity asset : scopedAssets) {
                var latestQuote = marketQuoteSnapshotRepository.findTop1ByAssetCodeOrderBySnapshotUtcDesc(asset.getAssetCode());
                if (latestQuote.isPresent()) {
                    quoteCollected++;
                    long delay = Duration.between(resolveQuoteTime(latestQuote.get()), now).getSeconds();
                    if (delay > maxQuoteDelaySeconds) {
                        delayedQuote++;
                    }
                }

                var latestBar = marketPriceBarRepository.findTop1ByAssetCodeAndTimeframeOrderByBarTimeDesc(asset.getAssetCode(), "1m");
                if (latestBar.isPresent()) {
                    barCollected++;
                    long delay = Duration.between(resolveBarTime(latestBar.get()), now).getSeconds();
                    if (delay > maxBarDelaySeconds) {
                        delayedBar++;
                    }
                    if (isOutlier(asset.getAssetCode())) {
                        anomalyCount++;
                    }
                }
            }

            QualityMetrics metrics = calculateQualityMetrics(
                    expected,
                    quoteCollected,
                    barCollected,
                    delayedQuote,
                    delayedBar,
                    0,
                    anomalyCount);

            MarketDataQualitySnapshotEntity snapshot = new MarketDataQualitySnapshotEntity();
            snapshot.setProvider(DEFAULT_PROVIDER);
            snapshot.setCountry(scope[0]);
            snapshot.setTheme(scope[1]);
            snapshot.setAssetCountExpected(expected);
            snapshot.setAssetCountCollected(Math.min(quoteCollected, barCollected));
            snapshot.setQuoteCountExpected(expected);
            snapshot.setQuoteCountCollected(quoteCollected);
            snapshot.setBarCountExpected(expected);
            snapshot.setBarCountCollected(barCollected);
            snapshot.setMissingRate(metrics.missingRate());
            snapshot.setDelayRate(metrics.delayRate());
            snapshot.setDuplicateRate(metrics.duplicateRate());
            snapshot.setAnomalyRate(metrics.anomalyRate());
            snapshot.setQualityScore(metrics.qualityScore());
            snapshot.setSummaryJson(toJson(Map.of(
                    "expected_assets", expected,
                    "quote_collected", quoteCollected,
                    "bar_collected", barCollected,
                    "delayed_quote", delayedQuote,
                    "delayed_bar", delayedBar,
                    "anomaly_count", anomalyCount,
                    "audit_sample_rate", auditSampleRate)));
            snapshot.setTraceId(traceId());
            marketDataQualitySnapshotRepository.save(snapshot);

            if (metrics.qualityScore().compareTo(minimumQualityScore) < 0) {
                saveGapEvent(
                        null,
                        DEFAULT_PROVIDER,
                        null,
                        MarketGapEventType.DELAYED_QUOTE,
                        AuditSeverityType.WARN,
                        now,
                        null,
                        null,
                        Map.of("quality_score", metrics.qualityScore(), "minimum_quality_score", minimumQualityScore));
            }
        }
    }

    /**
     * 수집 갭 이벤트를 탐지/기록한다.
     */
    @Transactional
    public void runMarketDataGapDetection() {
        OffsetDateTime now = OffsetDateTime.now();
        List<AssetUniverseEntity> assets = assetUniverseRepository.findByActiveTrueOrderByUpdatedAtDesc();
        for (AssetUniverseEntity asset : assets) {
            String assetCode = asset.getAssetCode();
            var quoteOpt = marketQuoteSnapshotRepository.findTop1ByAssetCodeOrderBySnapshotUtcDesc(assetCode);
            if (quoteOpt.isEmpty()) {
                saveGapEvent(
                        assetCode,
                        DEFAULT_PROVIDER,
                        null,
                        MarketGapEventType.DELAYED_QUOTE,
                        AuditSeverityType.WARN,
                        now.minusSeconds(maxQuoteDelaySeconds),
                        null,
                        maxQuoteDelaySeconds,
                        Map.of("reason", "quote_missing"));
            } else {
                OffsetDateTime quoteTime = resolveQuoteTime(quoteOpt.get());
                long delay = Duration.between(quoteTime, now).getSeconds();
                if (delay > maxQuoteDelaySeconds) {
                    saveGapEvent(
                            assetCode,
                            DEFAULT_PROVIDER,
                            null,
                            MarketGapEventType.DELAYED_QUOTE,
                            AuditSeverityType.WARN,
                            now.minusSeconds(maxQuoteDelaySeconds),
                            quoteTime,
                            delay,
                            Map.of("reason", "quote_delayed"));
                }
            }

            var barOpt = marketPriceBarRepository.findTop1ByAssetCodeAndTimeframeOrderByBarTimeDesc(assetCode, "1m");
            if (barOpt.isEmpty()) {
                saveGapEvent(
                        assetCode,
                        DEFAULT_PROVIDER,
                        "1m",
                        MarketGapEventType.MISSING_BAR,
                        AuditSeverityType.WARN,
                        now.minusSeconds(maxBarDelaySeconds),
                        null,
                        maxBarDelaySeconds,
                        Map.of("reason", "bar_missing"));
            } else {
                OffsetDateTime barTime = resolveBarTime(barOpt.get());
                long delay = Duration.between(barTime, now).getSeconds();
                if (delay > maxBarDelaySeconds) {
                    saveGapEvent(
                            assetCode,
                            DEFAULT_PROVIDER,
                            "1m",
                            MarketGapEventType.MISSING_BAR,
                            AuditSeverityType.WARN,
                            now.minusSeconds(maxBarDelaySeconds),
                            barTime,
                            delay,
                            Map.of("reason", "bar_delayed"));
                }
            }

            if (hasTimeReversal(assetCode, "1m")) {
                saveGapEvent(
                        assetCode,
                        DEFAULT_PROVIDER,
                        "1m",
                        MarketGapEventType.TIME_REVERSAL,
                        AuditSeverityType.ERROR,
                        now,
                        null,
                        null,
                        Map.of("reason", "time_reversal_detected"));
            }
        }
    }

    /**
     * API 응답 감사 로그 보관기간 정리.
     */
    @Transactional
    public void cleanupApiResponseAudit() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(Math.max(1, apiResponseAuditRetentionDays));
        long deleted = apiResponseAuditRepository.deleteByRequestTimeUtcBefore(cutoff);
        long resolvedDeleted = marketDataGapEventRepository.deleteByResolvedTrueAndResolvedAtBefore(cutoff);
        if (deleted > 0 || resolvedDeleted > 0) {
            log.info("Quality cleanup done: apiAuditDeleted={}, resolvedGapDeleted={}", deleted, resolvedDeleted);
        }
    }

    /**
     * 일 단위 품질 요약 로그 생성 골격.
     */
    @Transactional(readOnly = true)
    public void runDataQualitySummary() {
        List<MarketDataQualitySnapshotEntity> recent = marketDataQualitySnapshotRepository.findBySnapshotTimeUtcAfterOrderBySnapshotTimeUtcDesc(
                OffsetDateTime.now().minusHours(24));
        long unresolved = marketDataGapEventRepository.countByResolvedFalse();
        if (recent.isEmpty()) {
            log.info("No quality snapshot in last 24h. unresolved_gap_events={}", unresolved);
            return;
        }
        BigDecimal avgScore = recent.stream()
                .map(MarketDataQualitySnapshotEntity::getQualityScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_UP);
        log.info("Data quality summary: snapshot_count={}, avg_score={}, unresolved_gap_events={}",
                recent.size(),
                avgScore,
                unresolved);
    }

    /**
     * 외부 Provider 응답 감사 샘플 저장 골격.
     */
    @Transactional
    public void saveApiResponseAuditSample(
            String provider,
            String apiName,
            OffsetDateTime requestTimeUtc,
            OffsetDateTime responseTimeUtc,
            Integer httpStatus,
            Integer recordCount,
            String samplePayloadJson,
            boolean success,
            String errorCode) {
        ApiResponseAuditEntity entity = new ApiResponseAuditEntity();
        entity.setProvider(defaultIfBlank(provider, DEFAULT_PROVIDER));
        entity.setApiName(defaultIfBlank(apiName, "unknown-api"));
        entity.setRequestTimeUtc(requestTimeUtc == null ? OffsetDateTime.now() : requestTimeUtc);
        entity.setResponseTimeUtc(responseTimeUtc);
        if (requestTimeUtc != null && responseTimeUtc != null) {
            entity.setLatencyMs(Duration.between(requestTimeUtc, responseTimeUtc).toMillis());
        }
        entity.setHttpStatus(httpStatus);
        entity.setRecordCount(recordCount);
        entity.setSamplePayloadJson(sensitiveDataMaskingUtil.maskPayload(samplePayloadJson));
        entity.setSuccess(success);
        entity.setErrorCode(sensitiveDataMaskingUtil.sanitizeErrorCode(errorCode));
        entity.setTraceId(traceId());
        apiResponseAuditRepository.save(entity);
    }

    /**
     * 품질지표 계산식.
     * 누락률/지연률/중복률/이상치율을 0~1로 정규화하고, 가중합으로 점수(0~100)를 계산한다.
     */
    public QualityMetrics calculateQualityMetrics(
            int expectedAssets,
            int quoteCollected,
            int barCollected,
            int delayedQuoteCount,
            int delayedBarCount,
            int duplicateCount,
            int anomalyCount) {
        int safeExpected = Math.max(1, expectedAssets);
        int expectedTotal = safeExpected * 2;
        int collectedTotal = Math.max(0, quoteCollected) + Math.max(0, barCollected);
        int missingTotal = Math.max(0, expectedTotal - collectedTotal);
        int delayedTotal = Math.max(0, delayedQuoteCount) + Math.max(0, delayedBarCount);

        BigDecimal missingRate = ratio(missingTotal, expectedTotal);
        BigDecimal delayRate = ratio(delayedTotal, expectedTotal);
        BigDecimal duplicateRate = ratio(Math.max(0, duplicateCount), Math.max(1, barCollected));
        BigDecimal anomalyRate = ratio(Math.max(0, anomalyCount), Math.max(1, barCollected));

        BigDecimal weightedPenalty = missingRate.multiply(BigDecimal.valueOf(55))
                .add(delayRate.multiply(BigDecimal.valueOf(25)))
                .add(duplicateRate.multiply(BigDecimal.valueOf(10)))
                .add(anomalyRate.multiply(BigDecimal.valueOf(10)));
        BigDecimal qualityScore = BigDecimal.valueOf(100).subtract(weightedPenalty);
        qualityScore = qualityScore.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));

        return new QualityMetrics(
                missingRate.setScale(6, RoundingMode.HALF_UP),
                delayRate.setScale(6, RoundingMode.HALF_UP),
                duplicateRate.setScale(6, RoundingMode.HALF_UP),
                anomalyRate.setScale(6, RoundingMode.HALF_UP),
                qualityScore.setScale(2, RoundingMode.HALF_UP));
    }

    private void saveGapEvent(
            String assetCode,
            String provider,
            String timeframe,
            MarketGapEventType eventType,
            AuditSeverityType severity,
            OffsetDateTime expectedTimeUtc,
            OffsetDateTime actualTimeUtc,
            Long delaySeconds,
            Map<String, Object> detail) {
        MarketDataGapEventEntity event = new MarketDataGapEventEntity();
        event.setAssetCode(assetCode);
        event.setProvider(defaultIfBlank(provider, DEFAULT_PROVIDER));
        event.setTimeframe(timeframe);
        event.setEventType(eventType);
        event.setSeverity(severity);
        event.setExpectedTimeUtc(expectedTimeUtc);
        event.setActualTimeUtc(actualTimeUtc);
        event.setDelaySeconds(delaySeconds);
        event.setDetailJson(toJson(detail));
        event.setResolved(false);
        event.setTraceId(traceId());
        marketDataGapEventRepository.save(event);
    }

    private OffsetDateTime resolveQuoteTime(MarketQuoteSnapshotEntity quote) {
        if (quote.getQuoteTimeUtc() != null) {
            return quote.getQuoteTimeUtc();
        }
        if (quote.getSnapshotUtc() != null) {
            return quote.getSnapshotUtc();
        }
        return quote.getCreatedAt() == null ? OffsetDateTime.now() : quote.getCreatedAt();
    }

    private OffsetDateTime resolveBarTime(MarketPriceBarEntity bar) {
        if (bar.getBarTimeUtc() != null) {
            return bar.getBarTimeUtc();
        }
        if (bar.getBarTime() != null) {
            return bar.getBarTime();
        }
        return bar.getCreatedAt() == null ? OffsetDateTime.now() : bar.getCreatedAt();
    }

    private boolean hasTimeReversal(String assetCode, String timeframe) {
        List<MarketPriceBarEntity> bars = marketPriceBarRepository.findTop2ByAssetCodeAndTimeframeOrderByBarTimeDesc(assetCode, timeframe);
        if (bars.size() < 2) {
            return false;
        }
        OffsetDateTime current = resolveBarTime(bars.get(0));
        OffsetDateTime previous = resolveBarTime(bars.get(1));
        return current.isBefore(previous);
    }

    private boolean isOutlier(String assetCode) {
        List<MarketPriceBarEntity> bars = marketPriceBarRepository.findTop2ByAssetCodeAndTimeframeOrderByBarTimeDesc(assetCode, "1m");
        if (bars.size() < 2) {
            return false;
        }
        BigDecimal current = safeDecimal(bars.get(0).getClosePrice());
        BigDecimal previous = safeDecimal(bars.get(1).getClosePrice());
        if (previous.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal pct = current.subtract(previous)
                .abs()
                .divide(previous, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        return pct.compareTo(outlierThresholdPct.max(BigDecimal.ZERO)) > 0;
    }

    private BigDecimal ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(Math.max(0, numerator))
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private BigDecimal safeDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String groupKey(String country, String theme) {
        String safeCountry = defaultIfBlank(country, "UNKNOWN");
        String safeTheme = defaultIfBlank(theme, "ALL");
        return safeCountry + "::" + safeTheme;
    }

    private String[] splitGroup(String key) {
        String[] parts = key.split("::", 2);
        String country = parts.length > 0 ? parts[0] : "UNKNOWN";
        String theme = parts.length > 1 ? parts[1] : "ALL";
        return new String[]{country, theme};
    }

    private String defaultIfBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }

    /**
     * 품질 계산 결과 전달 모델.
     */
    public record QualityMetrics(
            BigDecimal missingRate,
            BigDecimal delayRate,
            BigDecimal duplicateRate,
            BigDecimal anomalyRate,
            BigDecimal qualityScore) {
    }
}

package com.wangbyul.gnd.batch.job;

import com.wangbyul.gnd.batch.otel.OtelBatchTracer;
import com.wangbyul.gnd.core.service.MarketDataCollectionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

/**
 * 시장데이터 price bar 수집 배치 잡.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class MarketPriceBarCollectionJob extends QuartzJobBean {

    private final MarketDataCollectionService marketDataCollectionService;
    private final OtelBatchTracer tracer;

    @Value("${app.market.collection.bar-timeframes:1m,1h,d1}")
    private List<String> barTimeframes;

    @Value("${app.market.collection.bar-points-per-asset-1m:240}")
    private int barPointsPerAsset1m;

    @Value("${app.market.collection.bar-points-per-asset-h1:240}")
    private int barPointsPerAssetH1;

    @Value("${app.market.collection.bar-points-per-asset-d1:365}")
    private int barPointsPerAssetD1;

    public MarketPriceBarCollectionJob(MarketDataCollectionService marketDataCollectionService, OtelBatchTracer tracer) {
        this.marketDataCollectionService = marketDataCollectionService;
        this.tracer = tracer;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        tracer.trace("batch.market-bar-collection", () -> {
            List<String> targets = normalizeTimeframes(barTimeframes);
            List<String> failures = new ArrayList<>();
            int totalSuccess = 0;
            int totalFailed = 0;
            String lastProvider = "";
            String lastStatus = "";
            boolean fallbackUsed = false;

            for (String timeframe : targets) {
                int points = barsPerAsset(timeframe);
                var result = marketDataCollectionService.collectBars(timeframe, points, "batch-quartz", null);
                totalSuccess += Math.max(0, result.successCount());
                totalFailed += Math.max(0, result.failedCount());
                fallbackUsed = fallbackUsed || result.fallbackUsed();
                lastProvider = result.providerName();
                lastStatus = result.status();
                if (!"SUCCESS".equalsIgnoreCase(result.status())) {
                    failures.add(timeframe + ":" + result.status());
                }
            }

            log.info("market bar collection finished provider={} status={} timeframes={} success={} failed={} fallback={} failures={}",
                    lastProvider,
                    lastStatus,
                    targets,
                    totalSuccess,
                    totalFailed,
                    fallbackUsed,
                    failures);
        });
    }

    private int barsPerAsset(String timeframe) {
        return switch (timeframe) {
            case "H1" -> Math.max(24, barPointsPerAssetH1);
            case "D1" -> Math.max(30, barPointsPerAssetD1);
            default -> Math.max(60, barPointsPerAsset1m);
        };
    }

    private List<String> normalizeTimeframes(List<String> raw) {
        List<String> input = (raw == null || raw.isEmpty()) ? List.of("1m", "1h", "d1") : raw;
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (String value : input) {
            String key = normalizeTimeframe(value);
            normalized.putIfAbsent(key, key);
        }
        return new ArrayList<>(normalized.values());
    }

    private String normalizeTimeframe(String raw) {
        if (raw == null || raw.isBlank()) {
            return "1m";
        }
        String value = raw.trim().toLowerCase();
        if ("1d".equals(value) || "d1".equals(value) || "day".equals(value) || "daily".equals(value)) {
            return "D1";
        }
        if ("1h".equals(value) || "h1".equals(value) || "60m".equals(value)) {
            return "H1";
        }
        return "1m";
    }
}

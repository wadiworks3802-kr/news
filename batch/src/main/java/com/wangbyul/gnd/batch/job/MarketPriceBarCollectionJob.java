package com.wangbyul.gnd.batch.job;

import com.wangbyul.gnd.batch.otel.OtelBatchTracer;
import com.wangbyul.gnd.core.service.MarketDataCollectionService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
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

    public MarketPriceBarCollectionJob(MarketDataCollectionService marketDataCollectionService, OtelBatchTracer tracer) {
        this.marketDataCollectionService = marketDataCollectionService;
        this.tracer = tracer;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        tracer.trace("batch.market-bar-collection", () -> {
            var result = marketDataCollectionService.collectBars("1m", "batch-quartz");
            log.info("market bar collection finished provider={} status={} success={} failed={} fallback={}",
                    result.providerName(),
                    result.status(),
                    result.successCount(),
                    result.failedCount(),
                    result.fallbackUsed());
        });
    }
}

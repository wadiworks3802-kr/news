package com.wangbyul.gnd.batch.job;

import com.wangbyul.gnd.batch.otel.OtelBatchTracer;
import com.wangbyul.gnd.core.service.MarketDataCollectionService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

/**
 * 시장데이터 Provider 헬스체크 배치 잡.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class MarketProviderHealthCheckJob extends QuartzJobBean {

    private final MarketDataCollectionService marketDataCollectionService;
    private final OtelBatchTracer tracer;

    public MarketProviderHealthCheckJob(MarketDataCollectionService marketDataCollectionService, OtelBatchTracer tracer) {
        this.marketDataCollectionService = marketDataCollectionService;
        this.tracer = tracer;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        tracer.trace("batch.market-provider-health-check", () -> {
            var result = marketDataCollectionService.runProviderHealthCheck("batch-quartz");
            log.info("market provider health-check finished provider={} status={} fallback={}",
                    result.providerName(),
                    result.status(),
                    result.fallbackUsed());
        });
    }
}

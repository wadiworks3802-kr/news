package com.wangbyul.gnd.batch.job;

import com.wangbyul.gnd.batch.otel.OtelBatchTracer;
import com.wangbyul.gnd.core.service.UniverseRebuildService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

/**
 * 유니버스 리빌드 배치 잡.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class UniverseRebuildJob extends QuartzJobBean {

    private final UniverseRebuildService universeRebuildService;
    private final OtelBatchTracer tracer;

    public UniverseRebuildJob(UniverseRebuildService universeRebuildService, OtelBatchTracer tracer) {
        this.universeRebuildService = universeRebuildService;
        this.tracer = tracer;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        tracer.trace("batch.universe-rebuild", () -> {
            var result = universeRebuildService.rebuildUniverse("batch");
            log.info("Universe rebuild done: totalAssets={}, coreAssets={}, countryFallback={}, themeFallback={}",
                    result.totalAssets(),
                    result.coreAssets(),
                    result.countryMinimumFallbackCount(),
                    result.themeMinimumFallbackCount());
        });
    }
}

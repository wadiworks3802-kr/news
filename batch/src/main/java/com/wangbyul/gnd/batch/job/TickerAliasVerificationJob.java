package com.wangbyul.gnd.batch.job;

import com.wangbyul.gnd.batch.otel.OtelBatchTracer;
import com.wangbyul.gnd.core.service.TickerAliasVerificationService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

/**
 * 티커 별칭 검증 배치 잡.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class TickerAliasVerificationJob extends QuartzJobBean {

    private final TickerAliasVerificationService tickerAliasVerificationService;
    private final OtelBatchTracer tracer;

    public TickerAliasVerificationJob(TickerAliasVerificationService tickerAliasVerificationService, OtelBatchTracer tracer) {
        this.tickerAliasVerificationService = tickerAliasVerificationService;
        this.tracer = tracer;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        tracer.trace("batch.ticker-alias-verification", () -> {
            var result = tickerAliasVerificationService.verifyAll("batch");
            log.info("Ticker alias verification done: aliasCount={}, failedChecks={}, errorCount={}",
                    result.aliasCount(),
                    result.failedChecks(),
                    result.errorCount());
        });
    }
}

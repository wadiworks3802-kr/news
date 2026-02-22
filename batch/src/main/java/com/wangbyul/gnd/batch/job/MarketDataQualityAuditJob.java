package com.wangbyul.gnd.batch.job;

import com.wangbyul.gnd.batch.otel.OtelBatchTracer;
import com.wangbyul.gnd.batch.service.DataQualityAuditService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

/**
 * 시장데이터 품질 스냅샷 배치 잡.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class MarketDataQualityAuditJob extends QuartzJobBean {

    private final DataQualityAuditService dataQualityAuditService;
    private final OtelBatchTracer tracer;

    public MarketDataQualityAuditJob(DataQualityAuditService dataQualityAuditService, OtelBatchTracer tracer) {
        this.dataQualityAuditService = dataQualityAuditService;
        this.tracer = tracer;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        tracer.trace("batch.market-quality-audit", dataQualityAuditService::runMarketDataQualityAudit);
    }
}

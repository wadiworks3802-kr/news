package com.wangbyul.gnd.batch.job;

import com.wangbyul.gnd.batch.otel.OtelBatchTracer;
import com.wangbyul.gnd.batch.service.DataQualityAuditService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

/**
 * 일 단위 데이터 품질 요약 배치 잡.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Component
@DisallowConcurrentExecution
public class DataQualitySummaryJob extends QuartzJobBean {

    private final DataQualityAuditService dataQualityAuditService;
    private final OtelBatchTracer tracer;

    public DataQualitySummaryJob(DataQualityAuditService dataQualityAuditService, OtelBatchTracer tracer) {
        this.dataQualityAuditService = dataQualityAuditService;
        this.tracer = tracer;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        tracer.trace("batch.data-quality-summary", dataQualityAuditService::runDataQualitySummary);
    }
}

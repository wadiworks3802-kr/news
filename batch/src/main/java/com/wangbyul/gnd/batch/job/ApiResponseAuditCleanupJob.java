package com.wangbyul.gnd.batch.job;

import com.wangbyul.gnd.batch.otel.OtelBatchTracer;
import com.wangbyul.gnd.batch.service.DataQualityAuditService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

/**
 * 외부 API 응답 감사 로그 정리 배치 잡.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Component
@DisallowConcurrentExecution
public class ApiResponseAuditCleanupJob extends QuartzJobBean {

    private final DataQualityAuditService dataQualityAuditService;
    private final OtelBatchTracer tracer;

    public ApiResponseAuditCleanupJob(DataQualityAuditService dataQualityAuditService, OtelBatchTracer tracer) {
        this.dataQualityAuditService = dataQualityAuditService;
        this.tracer = tracer;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        tracer.trace("batch.api-response-audit-cleanup", dataQualityAuditService::cleanupApiResponseAudit);
    }
}

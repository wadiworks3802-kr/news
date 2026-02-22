package com.wangbyul.gnd.batch.job;

import com.wangbyul.gnd.batch.otel.OtelBatchTracer;
import com.wangbyul.gnd.core.domain.SourceEntity;
import com.wangbyul.gnd.core.domain.SourceGrade;
import com.wangbyul.gnd.core.repository.SourceRepository;
import com.wangbyul.gnd.core.service.FetchService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@DisallowConcurrentExecution
/**
 * Quartz 수집 배치 잡.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * JobDataMap의 grade(P0/P1/P2/P3)에 따라 수집 대상을 선택하고,
 * 소스 우선순위 순으로 fetch 파이프라인을 실행한다.
 */
public class FetchJob extends QuartzJobBean {

    private final SourceRepository sourceRepository;
    private final FetchService fetchService;
    private final OtelBatchTracer tracer;

    public FetchJob(SourceRepository sourceRepository, FetchService fetchService, OtelBatchTracer tracer) {
        this.sourceRepository = sourceRepository;
        this.fetchService = fetchService;
        this.tracer = tracer;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        tracer.trace("batch.fetch", () -> {
            // 트리거에 설정된 grade 값을 읽어 해당 등급 소스만 수집
            String grade = context.getMergedJobDataMap().getString("grade");
            SourceGrade sourceGrade = SourceGrade.valueOf(grade);
            List<SourceEntity> sources = sourceRepository.findBySourceGradeAndAllowFetchTrueOrderByPriorityAsc(sourceGrade);
            for (SourceEntity source : sources) {
                try {
                    fetchService.fetch(source);
                } catch (Exception e) {
                    // 개별 소스 실패는 로그만 남기고 다음 소스로 진행
                    log.error("Fetch failed for source={}", source.getSid(), e);
                }
            }
        });
    }
}

package com.wangbyul.gnd.batch.job;

import com.wangbyul.gnd.batch.otel.OtelBatchTracer;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.exception.HumanReviewRequiredException;
import com.wangbyul.gnd.core.repository.NewsRepository;
import com.wangbyul.gnd.core.service.DlqPublisher;
import com.wangbyul.gnd.core.service.NlpPipelineService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;
/**
 * NlpJob 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Slf4j
@Component
@DisallowConcurrentExecution
public class NlpJob extends QuartzJobBean {

    private final NewsRepository newsRepository;
    private final NlpPipelineService nlpPipelineService;
    private final DlqPublisher dlqPublisher;
    private final OtelBatchTracer tracer;

    public NlpJob(
            NewsRepository newsRepository,
            NlpPipelineService nlpPipelineService,
            DlqPublisher dlqPublisher,
            OtelBatchTracer tracer) {
        this.newsRepository = newsRepository;
        this.nlpPipelineService = nlpPipelineService;
        this.dlqPublisher = dlqPublisher;
        this.tracer = tracer;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        tracer.trace("batch.nlp", () -> {
            List<NewsEntity> pending = newsRepository.findTop100BySummaryKoIsNullOrderByPubUtcDesc();
            for (NewsEntity news : pending) {
                try {
                    NewsEntity enriched = nlpPipelineService.process(news);
                    newsRepository.save(enriched);
                } catch (HumanReviewRequiredException e) {
                    dlqPublisher.publish(news.getId(), news.getUrl(), e.getReason().name());
                } catch (Exception e) {
                    log.error("NLP failed for news={}", news.getId(), e);
                }
            }
        });
    }
}

package com.wangbyul.gnd.batch.job;

import com.wangbyul.gnd.batch.otel.OtelBatchTracer;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.repository.NewsRepository;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@DisallowConcurrentExecution
/**
 * TTL 만료 데이터 정리 배치 잡.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 오래된 뉴스를 순차적으로 확인해 만료된 항목만 삭제한다.
 */
public class CleanupJob extends QuartzJobBean {

    private final NewsRepository newsRepository;
    private final OtelBatchTracer tracer;
    @Value("${app.news.max-retention-days:7}")
    private int maxRetentionDays;

    public CleanupJob(NewsRepository newsRepository, OtelBatchTracer tracer) {
        this.newsRepository = newsRepository;
        this.tracer = tracer;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        tracer.trace("batch.cleanup", () -> {
            OffsetDateTime now = OffsetDateTime.now();
            int retentionDays = Math.max(1, maxRetentionDays);

            // 하드 보관 상한(기본 7일)을 우선 적용해 장기 적재를 방지
            OffsetDateTime hardCutoff = now.minusDays(retentionDays);
            long hardDeleted = newsRepository.deleteByCreatedAtBefore(hardCutoff);

            // 한 번에 너무 많은 레코드를 건드리지 않도록 TTL 정리는 배치 크기 제한
            List<NewsEntity> rows = newsRepository.findTop500ByOrderByCreatedAtAsc();
            int ttlDeleted = 0;
            for (NewsEntity news : rows) {
                int ttlSeconds = news.getTtl() == null ? retentionDays * 24 * 3600 : news.getTtl();
                ttlSeconds = Math.max(60, Math.min(ttlSeconds, retentionDays * 24 * 3600));
                OffsetDateTime expiry = news.getCreatedAt().plusSeconds(ttlSeconds);
                if (expiry.isBefore(now)) {
                    // TTL 만료된 데이터만 삭제
                    newsRepository.delete(news);
                    ttlDeleted++;
                }
            }

            if (hardDeleted > 0 || ttlDeleted > 0) {
                log.info("Cleanup done: hardDeleted={}, ttlDeleted={}, retentionDays={}", hardDeleted, ttlDeleted, retentionDays);
            }
        });
    }
}

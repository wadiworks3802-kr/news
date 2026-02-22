package com.wangbyul.gnd.api.service;

import com.wangbyul.gnd.core.repository.NewsRepository;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 한국어 번역 사전 적재 스케줄러.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 최근 뉴스를 주기적으로 번역 큐에 넣어 첫 조회 지연을 줄인다.
 */
@Slf4j
@Component
public class TranslationPrefetchScheduler {

    private final NewsRepository newsRepository;
    private final NewsLocalizationService newsLocalizationService;

    @Value("${app.translation.ko.prefetch-enabled:true}")
    private boolean prefetchEnabled;

    @Value("${app.translation.ko.prefetch-window-hours:24}")
    private int prefetchWindowHours;

    @Value("${app.translation.ko.prefetch-limit:120}")
    private int prefetchLimit;

    public TranslationPrefetchScheduler(
            NewsRepository newsRepository,
            NewsLocalizationService newsLocalizationService) {
        this.newsRepository = newsRepository;
        this.newsLocalizationService = newsLocalizationService;
    }

    @Scheduled(fixedDelayString = "${app.translation.ko.prefetch-interval-ms:45000}")
    public void prefetchRecentTranslations() {
        if (!prefetchEnabled) {
            return;
        }

        OffsetDateTime since = OffsetDateTime.now().minusHours(Math.max(1, prefetchWindowHours));
        int safeLimit = Math.max(20, prefetchLimit);

        int queued = newsLocalizationService.prefetchTranslations(newsRepository.findTop300ByOrderByPubUtcDesc().stream()
                .filter(news -> news.getPubUtc() != null && news.getPubUtc().isAfter(since))
                .limit(safeLimit)
                .toList());

        if (queued > 0) {
            log.debug("translation prefetch queued={}", queued);
        }
    }
}

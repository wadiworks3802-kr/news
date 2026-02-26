package com.wangbyul.gnd.api.service;

import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.repository.NewsRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 뉴스 한국어 현지화(비동기 번역 큐) 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 설계 의도:
 * - 사용자 요청 경로에서 동기 번역을 제거해 응답 지연을 줄인다.
 * - 번역이 필요한 뉴스는 백그라운드 큐로 처리하고 결과를 DB에 저장한다.
 */
@Service
public class NewsLocalizationService {

    private final KoreanTranslationService koreanTranslationService;
    private final NewsRepository newsRepository;
    private final Executor newsTranslationExecutor;
    private final Set<String> inFlightIds = ConcurrentHashMap.newKeySet();

    public NewsLocalizationService(
            KoreanTranslationService koreanTranslationService,
            NewsRepository newsRepository,
            @Qualifier("newsTranslationExecutor") Executor newsTranslationExecutor) {
        this.koreanTranslationService = koreanTranslationService;
        this.newsRepository = newsRepository;
        this.newsTranslationExecutor = newsTranslationExecutor;
    }

    /**
     * 화면 노출용 제목/요약을 반환한다.
     * ko 조회 시 번역이 미완료면 즉시 원문 fallback을 반환하고 비동기 번역을 예약한다.
     */
    public LocalizedContent localizeForView(NewsEntity entity, String viewLang) {
        String rawTitle = normalizeDisplayText(defaultIfBlank(entity.getTitleRaw(), "(제목 없음)"), "(제목 없음)");
        String rawSummary = buildRawSummary(entity.getBodyRaw());

        if ("raw".equalsIgnoreCase(viewLang)) {
            return new LocalizedContent(rawTitle, rawSummary, false);
        }

        String titleKo = normalizeDisplayText(defaultIfBlank(entity.getTitleKo(), rawTitle), rawTitle);
        String summaryKo = normalizeDisplayText(defaultIfBlank(entity.getSummaryKo(), rawSummary), rawSummary);

        boolean pending = shouldQueueTranslation(entity, titleKo, summaryKo);
        if (pending) {
            queueTranslation(entity.getId());
        }
        return new LocalizedContent(titleKo, summaryKo, pending);
    }

    /**
     * 목록 전체에 대해 번역 큐를 선예약한다.
     * 첫 조회 이후 다음 조회부터는 번역본이 빠르게 노출되도록 돕는다.
     */
    public int prefetchTranslations(List<NewsEntity> entities) {
        int queued = 0;
        for (NewsEntity entity : entities) {
            String rawTitle = defaultIfBlank(entity.getTitleRaw(), "");
            String rawSummary = defaultIfBlank(entity.getSummaryKo(), buildRawSummary(entity.getBodyRaw()));
            if (!shouldQueueTranslation(entity, rawTitle, rawSummary)) {
                continue;
            }
            if (queueTranslation(entity.getId())) {
                queued++;
            }
        }
        return queued;
    }

    /**
     * 단일 뉴스를 번역 큐에 넣는다.
     * 이미 처리 중인 id는 중복 enqueue 하지 않는다.
     */
    public boolean queueTranslation(String newsId) {
        if (newsId == null || newsId.isBlank()) {
            return false;
        }
        if (!inFlightIds.add(newsId)) {
            return false;
        }
        newsTranslationExecutor.execute(() -> {
            try {
                translateAndPersist(newsId);
            } finally {
                inFlightIds.remove(newsId);
            }
        });
        return true;
    }

    /**
     * 사용자가 수동 번역 버튼을 눌렀을 때 즉시 번역을 수행한다.
     * 처리 중인 동일 뉴스는 중복 실행하지 않고 false를 반환한다.
     */
    public boolean translateNow(String newsId) {
        if (newsId == null || newsId.isBlank()) {
            return false;
        }
        if (!inFlightIds.add(newsId)) {
            return false;
        }
        try {
            translateAndPersist(newsId);
            return true;
        } finally {
            inFlightIds.remove(newsId);
        }
    }

    private void translateAndPersist(String newsId) {
        newsRepository.findById(newsId).ifPresent(news -> {
            String rawTitle = normalizeDisplayText(defaultIfBlank(news.getTitleRaw(), "(제목 없음)"), "(제목 없음)");
            String rawSummary = buildRawSummary(news.getBodyRaw());

            String translatedTitle = koreanTranslationService.toKorean(rawTitle);
            String translatedSummary = koreanTranslationService.toKorean(rawSummary);

            boolean changed = false;
            if (!translatedTitle.equals(defaultIfBlank(news.getTitleKo(), rawTitle))) {
                news.setTitleKo(translatedTitle);
                changed = true;
            }
            if (!translatedSummary.equals(defaultIfBlank(news.getSummaryKo(), rawSummary))) {
                news.setSummaryKo(translatedSummary);
                changed = true;
            }
            if (changed) {
                news.setTranslatedAtUtc(OffsetDateTime.now());
                newsRepository.save(news);
            }
        });
    }

    private boolean shouldQueueTranslation(NewsEntity entity, String titleKo, String summaryKo) {
        if (entity == null || "ko".equalsIgnoreCase(defaultIfBlank(entity.getLang(), ""))) {
            return false;
        }
        return !koreanTranslationService.isKoreanText(titleKo)
                || !koreanTranslationService.isKoreanText(summaryKo);
    }

    private String buildRawSummary(String bodyRaw) {
        String plain = decodeHtmlEntities(defaultIfBlank(bodyRaw, ""))
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\u00A0", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (plain.isBlank()) {
            return "(요약 없음)";
        }
        if (plain.length() <= 320) {
            return plain;
        }
        return plain.substring(0, 317) + "...";
    }

    private String defaultIfBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String normalizeDisplayText(String value, String fallback) {
        String normalized = decodeHtmlEntities(defaultIfBlank(value, ""))
                .replaceAll("\u00A0", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.isBlank() ? defaultIfBlank(fallback, "") : normalized;
    }

    private String decodeHtmlEntities(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String decoded = input;
        for (int i = 0; i < 2; i++) {
            String next = decoded
                    .replace("&nbsp;", " ")
                    .replace("&#160;", " ")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&amp;", "&");
            if (next.equals(decoded)) {
                break;
            }
            decoded = next;
        }
        return decoded;
    }

    public record LocalizedContent(String title, String summary, boolean translationPending) {
    }
}

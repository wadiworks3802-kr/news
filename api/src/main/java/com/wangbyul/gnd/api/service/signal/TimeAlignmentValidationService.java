package com.wangbyul.gnd.api.service.signal;

import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.service.signal.model.TimeAlignmentValidationResult;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.NewsRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 뉴스-가격 시간정렬 검증 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 1차 구현은 look-ahead 방지와 시간 역전(발행>수집) 검증을 우선 제공한다.
 */
@Service
public class TimeAlignmentValidationService {

    private final NewsRepository newsRepository;
    private final MarketPriceBarRepository marketPriceBarRepository;
    private final SignalPolicyProperties signalPolicyProperties;

    public TimeAlignmentValidationService(
            NewsRepository newsRepository,
            MarketPriceBarRepository marketPriceBarRepository,
            SignalPolicyProperties signalPolicyProperties) {
        this.newsRepository = newsRepository;
        this.marketPriceBarRepository = marketPriceBarRepository;
        this.signalPolicyProperties = signalPolicyProperties;
    }

    public TimeAlignmentValidationResult validateForSignal(AssetUniverseEntity asset, OffsetDateTime signalGeneratedAt) {
        OffsetDateTime signalTime = signalGeneratedAt == null ? OffsetDateTime.now() : signalGeneratedAt;
        int windowMinutes = Math.max(1, signalPolicyProperties.getNewsPriceAlignmentWindowMinutes());
        OffsetDateTime from = signalTime.minusMinutes(windowMinutes);
        List<NewsEntity> recentNews = newsRepository.findTop500ByCountryAndPublishedAtUtcAfterOrderByPublishedAtUtcDesc(
                asset.getCountry(), from);
        if (recentNews.isEmpty()) {
            recentNews = newsRepository.findTop500ByCountryAndPubUtcAfterOrderByPubUtcDesc(asset.getCountry(), from);
        }

        int invalidOrderCount = 0;
        int delayedTranslationCount = 0;
        List<String> warnings = new ArrayList<>();
        for (NewsEntity news : recentNews) {
            OffsetDateTime published = firstNonNull(news.getPublishedAtUtc(), news.getPubUtc());
            OffsetDateTime fetched = firstNonNull(news.getFetchedAtUtc(), news.getFetchUtc(), news.getCreatedAt());
            if (published != null && fetched != null && published.isAfter(fetched)) {
                invalidOrderCount++;
            }

            OffsetDateTime translatedAt = news.getTranslatedAtUtc();
            if (translatedAt != null && fetched != null) {
                long delaySeconds = Duration.between(fetched, translatedAt).getSeconds();
                if (delaySeconds > signalPolicyProperties.getTranslationDelayPenaltyThresholdSeconds()) {
                    delayedTranslationCount++;
                }
            }
        }

        boolean futureDataDetected = false;
        if (Boolean.TRUE.equals(signalPolicyProperties.getBlockFutureData())) {
            futureDataDetected = marketPriceBarRepository.findTop1ByAssetCodeAndTimeframeOrderByBarTimeDesc(
                            asset.getAssetCode(), "1m")
                    .map(bar -> {
                        OffsetDateTime barTime = firstNonNull(bar.getBarTimeUtc(), bar.getBarTime(), bar.getCreatedAt());
                        return barTime != null && barTime.isAfter(signalTime);
                    })
                    .orElse(false);
        }

        if (invalidOrderCount > 0) {
            warnings.add("news_publish_after_fetch:" + invalidOrderCount);
        }
        if (delayedTranslationCount > 0) {
            warnings.add("translation_delay_over_threshold:" + delayedTranslationCount);
        }
        if (futureDataDetected) {
            warnings.add("future_market_data_blocked");
        }

        BigDecimal penalty = BigDecimal.ZERO;
        if (!recentNews.isEmpty()) {
            BigDecimal invalidRatio = BigDecimal.valueOf(invalidOrderCount)
                    .divide(BigDecimal.valueOf(recentNews.size()), 6, RoundingMode.HALF_UP);
            BigDecimal delayedRatio = BigDecimal.valueOf(delayedTranslationCount)
                    .divide(BigDecimal.valueOf(recentNews.size()), 6, RoundingMode.HALF_UP);
            penalty = penalty
                    .add(invalidRatio.multiply(BigDecimal.valueOf(0.30d)))
                    .add(delayedRatio.multiply(BigDecimal.valueOf(0.15d)));
        }
        if (futureDataDetected) {
            penalty = penalty.add(BigDecimal.valueOf(0.25d));
        }
        penalty = penalty.min(BigDecimal.valueOf(0.60d)).max(BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP);

        return new TimeAlignmentValidationResult(
                invalidOrderCount,
                delayedTranslationCount,
                futureDataDetected,
                penalty,
                warnings);
    }

    private OffsetDateTime firstNonNull(OffsetDateTime... candidates) {
        for (OffsetDateTime candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }
}

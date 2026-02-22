package com.wangbyul.gnd.api.service.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.service.signal.model.TimeAlignmentValidationResult;
import com.wangbyul.gnd.core.domain.AssetType;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.NewsRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * TimeAlignmentValidationService 단위 테스트 초안.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@ExtendWith(MockitoExtension.class)
class TimeAlignmentValidationServiceTest {

    @Mock
    private NewsRepository newsRepository;
    @Mock
    private MarketPriceBarRepository marketPriceBarRepository;

    @Test
    void validateShouldDetectPublishFetchOrderIssueAndFutureData() {
        SignalPolicyProperties properties = new SignalPolicyProperties();
        properties.setNewsPriceAlignmentWindowMinutes(60);
        properties.setTranslationDelayPenaltyThresholdSeconds(300);
        properties.setBlockFutureData(true);

        TimeAlignmentValidationService service = new TimeAlignmentValidationService(
                newsRepository,
                marketPriceBarRepository,
                properties);

        OffsetDateTime now = OffsetDateTime.now();
        NewsEntity problematicNews = new NewsEntity();
        problematicNews.setCountry("KR");
        problematicNews.setPublishedAtUtc(now.minusMinutes(5));
        problematicNews.setFetchedAtUtc(now.minusMinutes(10));
        problematicNews.setTranslatedAtUtc(now.minusMinutes(2));
        problematicNews.setCreatedAt(now.minusMinutes(10));
        when(newsRepository.findTop500ByCountryAndPublishedAtUtcAfterOrderByPublishedAtUtcDesc(eq("KR"), any()))
                .thenReturn(List.of(problematicNews));

        MarketPriceBarEntity futureBar = new MarketPriceBarEntity();
        futureBar.setAssetCode("KR-TEST");
        futureBar.setBarTimeUtc(now.plusMinutes(1));
        futureBar.setClosePrice(BigDecimal.ONE);
        when(marketPriceBarRepository.findTop1ByAssetCodeAndTimeframeOrderByBarTimeDesc("KR-TEST", "1m"))
                .thenReturn(Optional.of(futureBar));

        AssetUniverseEntity asset = new AssetUniverseEntity();
        asset.setAssetCode("KR-TEST");
        asset.setAssetName("테스트");
        asset.setCountry("KR");
        asset.setAssetType(AssetType.STOCK);

        TimeAlignmentValidationResult result = service.validateForSignal(asset, now);

        assertThat(result.invalidPublishFetchOrderCount()).isEqualTo(1);
        assertThat(result.delayedTranslationCount()).isEqualTo(1);
        assertThat(result.futureDataDetected()).isTrue();
        assertThat(result.penaltyRate()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.warnings()).isNotEmpty();
    }
}

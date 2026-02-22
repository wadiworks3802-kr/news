package com.wangbyul.gnd.api.service.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.service.signal.model.ScalpSignalResult;
import com.wangbyul.gnd.core.domain.AssetType;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.repository.NewsRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RULE_V1 뉴스 확률 계산 단위 테스트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@ExtendWith(MockitoExtension.class)
class ScalpNewsSignalServiceTest {

    @Mock
    private NewsRepository newsRepository;

    @Test
    void insufficientSampleShouldReturnNeutralProbability() {
        SignalPolicyProperties properties = defaultProperties();
        ScalpNewsSignalService service = new ScalpNewsSignalService(newsRepository, properties, new ObjectMapper());

        OffsetDateTime now = OffsetDateTime.now();
        when(newsRepository.findTop500ByCountryAndPubUtcAfterOrderByPubUtcDesc(any(), any()))
                .thenReturn(List.of(
                        news("KR", "AAPL", "AAPL surge on growth outlook", "positive body", now.minusMinutes(10), 0.8),
                        news("KR", "AAPL", "AAPL rise as demand expands", "positive body", now.minusMinutes(20), 0.75)));

        AssetUniverseEntity asset = asset("AAPL", "Apple", "KR", "TECH");
        ScalpSignalResult result = service.analyze(asset, now, 60);

        assertThat(result.goodNewsProbability()).isEqualByComparingTo("0.5000");
        assertThat(result.badNewsProbability()).isEqualByComparingTo("0.5000");
        assertThat(result.newsConfidence()).isGreaterThanOrEqualTo(properties.getInsufficientSampleConfidenceFloor());
    }

    @Test
    void trustAndFutureFilterShouldBeApplied() {
        SignalPolicyProperties properties = defaultProperties();
        ScalpNewsSignalService service = new ScalpNewsSignalService(newsRepository, properties, new ObjectMapper());

        OffsetDateTime now = OffsetDateTime.now();
        when(newsRepository.findTop500ByCountryAndPubUtcAfterOrderByPubUtcDesc(any(), any()))
                .thenReturn(List.of(
                        news("KR", "AAPL", "AAPL surge and growth", "good signal", now.minusMinutes(10), 0.8),
                        news("KR", "AAPL", "AAPL drop risk recession", "bad signal", now.minusMinutes(11), 0.9),
                        news("KR", "AAPL", "AAPL rise but low trust", "low trust", now.minusMinutes(9), 0.2),
                        news("KR", "AAPL", "AAPL surge tomorrow", "future signal", now.plusMinutes(1), 0.9)));

        AssetUniverseEntity asset = asset("AAPL", "Apple", "KR", "TECH");
        ScalpSignalResult result = service.analyze(asset, now, 60);

        assertThat(result.matchedNewsCount()).isEqualTo(2);
        assertThat(result.probabilityReasonBreakdownJson()).contains("\"filtered_trust_count\":1");
        assertThat(result.probabilityReasonBreakdownJson()).contains("\"filtered_future_count\":1");
        assertThat(result.goodNewsProbability().add(result.badNewsProbability()))
                .isEqualByComparingTo("1.0000");
    }

    private SignalPolicyProperties defaultProperties() {
        SignalPolicyProperties properties = new SignalPolicyProperties();
        properties.setNewsWindowsMinutes(List.of(10, 30, 60));
        properties.setMinNewsCountForProbability(3);
        properties.setNewsTrustScoreMin(BigDecimal.valueOf(0.4d));
        properties.setPositiveSentimentThreshold(BigDecimal.valueOf(0.55d));
        properties.setNegativeSentimentThreshold(BigDecimal.valueOf(0.55d));
        properties.setNewsDecayHalfLifeMinutes(180);
        properties.setProbabilityCalculationMode("RULE_V1");
        properties.setInsufficientSampleConfidenceFloor(BigDecimal.valueOf(0.2d));
        properties.setNewsPriceAlignmentWindowMinutes(60);
        return properties;
    }

    private AssetUniverseEntity asset(String code, String name, String country, String theme) {
        AssetUniverseEntity entity = new AssetUniverseEntity();
        entity.setAssetCode(code);
        entity.setAssetName(name);
        entity.setCountry(country);
        entity.setTheme(theme);
        entity.setAssetType(AssetType.STOCK);
        return entity;
    }

    private NewsEntity news(String country, String keyword, String title, String body, OffsetDateTime publishedAt, double trustScore) {
        NewsEntity entity = new NewsEntity();
        entity.setCountry(country);
        entity.setTitleRaw(title + " " + keyword);
        entity.setSummaryKo(title);
        entity.setBodyRaw(body);
        entity.setPublishedAtUtc(publishedAt);
        entity.setPubUtc(publishedAt);
        entity.setFetchedAtUtc(publishedAt.plusMinutes(1));
        entity.setFetchUtc(publishedAt.plusMinutes(1));
        entity.setTrustScore(BigDecimal.valueOf(trustScore));
        entity.setCreatedAt(publishedAt.plusMinutes(1));
        return entity;
    }
}

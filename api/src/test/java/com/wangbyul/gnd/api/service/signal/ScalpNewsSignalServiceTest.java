package com.wangbyul.gnd.api.service.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.service.signal.model.ScalpSignalResult;
import com.wangbyul.gnd.core.domain.AssetType;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.domain.NewsAssetLinkEntity;
import com.wangbyul.gnd.core.domain.NewsLinkType;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.NewsAssetLinkRepository;
import com.wangbyul.gnd.core.repository.NewsRepository;
import com.wangbyul.gnd.core.repository.TickerAliasDictionaryRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    @Mock
    private NewsAssetLinkRepository newsAssetLinkRepository;
    @Mock
    private TickerAliasDictionaryRepository tickerAliasDictionaryRepository;
    @Mock
    private MarketPriceBarRepository marketPriceBarRepository;
    @Mock
    private MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;

    @Test
    void insufficientSampleShouldReturnStateInsteadOfForcedFiftyFifty() {
        SignalPolicyProperties properties = defaultProperties();
        ScalpNewsSignalService service = newService(properties);

        OffsetDateTime now = OffsetDateTime.now();
        stubInfra();
        when(newsRepository.findTop500ByCountryAndPubUtcAfterOrderByPubUtcDesc(any(), any()))
                .thenReturn(List.of(
                        news("KR", "AAPL", "AAPL surge on growth outlook", "positive body", now.minusMinutes(10), 0.8),
                        news("KR", "AAPL", "AAPL rise as demand expands", "positive body", now.minusMinutes(20), 0.75)));

        AssetUniverseEntity asset = asset("AAPL", "Apple", "KR", "TECH");
        ScalpSignalResult result = service.analyze(asset, now, 60);

        assertThat(result.goodNewsProbability()).isNotEqualByComparingTo("0.5000");
        assertThat(result.badNewsProbability()).isNotEqualByComparingTo("0.5000");
        assertThat(result.newsConfidence()).isGreaterThanOrEqualTo(properties.getInsufficientSampleConfidenceFloor());
        assertThat(result.probabilityReasonBreakdownJson()).contains("\"data_state\":\"INSUFFICIENT_DATA\"");
        assertThat(result.probabilityReasonBreakdownJson()).contains("\"analysis_state\":\"INSUFFICIENT_DATA\"");
    }

    @Test
    void trustAndFutureFilterShouldBeApplied() {
        SignalPolicyProperties properties = defaultProperties();
        ScalpNewsSignalService service = newService(properties);

        OffsetDateTime now = OffsetDateTime.now();
        stubInfra();
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
        assertThat(result.probabilityReasonBreakdownJson()).contains("\"news_alignment_result\"");
        verify(newsAssetLinkRepository, atLeastOnce()).save(any(NewsAssetLinkEntity.class));
    }

    @Test
    void eventDrivenNewsShouldReduceFiftyFiftyConvergence() {
        SignalPolicyProperties properties = defaultProperties();
        properties.setMinNewsCountForProbability(2);
        ScalpNewsSignalService service = newService(properties);
        OffsetDateTime now = OffsetDateTime.now();
        stubInfra();
        when(newsRepository.findTop500ByCountryAndPubUtcAfterOrderByPubUtcDesc(any(), any()))
                .thenReturn(List.of(
                        news("KR", "AAPL", "AAPL earnings beat and guidance raise", "earnings beat growth upgrade", now.minusMinutes(10), 0.9),
                        news("KR", "AAPL", "AAPL wins major defense contract order", "contract order deal growth", now.minusMinutes(20), 0.85),
                        news("KR", "AAPL", "AAPL factory accident risk outage", "accident outage risk", now.minusMinutes(30), 0.7)));

        ScalpSignalResult result = service.analyze(asset("AAPL", "Apple", "KR", "AI"), now, 60);
        BigDecimal good = result.goodNewsProbability();
        BigDecimal bad = result.badNewsProbability();
        assertThat(good.subtract(bad).abs()).isGreaterThan(new BigDecimal("0.0500"));
        assertThat(result.probabilityReasonBreakdownJson()).contains("\"top_positive_factors\"");
        assertThat(result.probabilityReasonBreakdownJson()).contains("\"top_negative_factors\"");
        assertThat(result.probabilityReasonBreakdownJson()).contains("\"explain_text\"");
    }

    @Test
    void mappingEvidenceShouldBeStoredWithEventFields() {
        SignalPolicyProperties properties = defaultProperties();
        ScalpNewsSignalService service = newService(properties);
        OffsetDateTime now = OffsetDateTime.now();
        stubInfra();
        when(newsRepository.findTop500ByCountryAndPubUtcAfterOrderByPubUtcDesc(any(), any()))
                .thenReturn(List.of(news("KR", "AAPL", "AAPL wins contract order", "order contract growth", now.minusMinutes(5), 0.8)));

        service.analyze(asset("AAPL", "Apple", "KR", "ENERGY"), now, 60);

        ArgumentCaptor<NewsAssetLinkEntity> captor = ArgumentCaptor.forClass(NewsAssetLinkEntity.class);
        verify(newsAssetLinkRepository).save(captor.capture());
        NewsAssetLinkEntity saved = captor.getValue();
        assertThat(saved.getLinkMethod()).isNotBlank();
        assertThat(saved.getEventType()).isNotBlank();
        assertThat(saved.getImpactDirection()).isNotBlank();
        assertThat(saved.getKeywordHitsJson()).contains("event_type");
        assertThat(saved.getLinkConfidence()).isNotNull();
    }

    private ScalpNewsSignalService newService(SignalPolicyProperties properties) {
        return new ScalpNewsSignalService(
                newsRepository,
                newsAssetLinkRepository,
                tickerAliasDictionaryRepository,
                marketPriceBarRepository,
                marketQuoteSnapshotRepository,
                properties,
                new ObjectMapper());
    }

    private void stubInfra() {
        when(tickerAliasDictionaryRepository.findByAssetCodeAndActiveTrueOrderByAliasTypeAsc(anyString()))
                .thenReturn(List.of());
        when(newsAssetLinkRepository.findByNewsIdAndAssetCodeAndLinkType(anyString(), anyString(), any(NewsLinkType.class)))
                .thenReturn(Optional.empty());
        when(newsRepository.findTop500ByCountryAndPublishedAtUtcAfterOrderByPublishedAtUtcDesc(any(), any()))
                .thenReturn(List.of());
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
        entity.setId(country + "-" + keyword + "-" + Math.abs(publishedAt.toEpochSecond()));
        entity.setTitleRaw(title + " " + keyword);
        entity.setSummaryKo(title);
        entity.setBodyRaw(body);
        entity.setPublishedAtUtc(publishedAt);
        entity.setPubUtc(publishedAt);
        entity.setFetchedAtUtc(publishedAt.plusMinutes(1));
        entity.setFetchUtc(publishedAt.plusMinutes(1));
        entity.setTrustScore(BigDecimal.valueOf(trustScore));
        entity.setDedupGroupId(keyword + "-" + publishedAt.getMinute());
        entity.setCreatedAt(publishedAt.plusMinutes(1));
        return entity;
    }
}

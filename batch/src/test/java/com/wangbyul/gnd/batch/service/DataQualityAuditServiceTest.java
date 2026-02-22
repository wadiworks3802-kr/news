package com.wangbyul.gnd.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.ApiResponseAuditEntity;
import com.wangbyul.gnd.core.repository.ApiResponseAuditRepository;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketDataGapEventRepository;
import com.wangbyul.gnd.core.repository.MarketDataQualitySnapshotRepository;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.util.SensitiveDataMaskingUtil;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * DataQualityAuditService 단위 테스트 초안.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@ExtendWith(MockitoExtension.class)
class DataQualityAuditServiceTest {

    @Mock
    private AssetUniverseRepository assetUniverseRepository;
    @Mock
    private MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    @Mock
    private MarketPriceBarRepository marketPriceBarRepository;
    @Mock
    private MarketDataQualitySnapshotRepository marketDataQualitySnapshotRepository;
    @Mock
    private MarketDataGapEventRepository marketDataGapEventRepository;
    @Mock
    private ApiResponseAuditRepository apiResponseAuditRepository;

    @Test
    void calculateQualityMetricsShouldReturnLowerScoreWhenMissingRateHigh() {
        DataQualityAuditService service = new DataQualityAuditService(
                assetUniverseRepository,
                marketQuoteSnapshotRepository,
                marketPriceBarRepository,
                marketDataQualitySnapshotRepository,
                marketDataGapEventRepository,
                apiResponseAuditRepository,
                new ObjectMapper(),
                new SensitiveDataMaskingUtil());

        DataQualityAuditService.QualityMetrics metrics = service.calculateQualityMetrics(
                10,
                2,
                1,
                4,
                4,
                1,
                1);

        assertThat(metrics.missingRate()).isGreaterThan(new BigDecimal("0.5"));
        assertThat(metrics.qualityScore()).isLessThan(new BigDecimal("50"));
    }

    @Test
    void saveApiResponseAuditSampleShouldMaskSensitiveFields() {
        DataQualityAuditService service = new DataQualityAuditService(
                assetUniverseRepository,
                marketQuoteSnapshotRepository,
                marketPriceBarRepository,
                marketDataQualitySnapshotRepository,
                marketDataGapEventRepository,
                apiResponseAuditRepository,
                new ObjectMapper(),
                new SensitiveDataMaskingUtil());
        ReflectionTestUtils.setField(service, "apiResponseAuditRetentionDays", 7);

        String payload = "{\"api_key\":\"ABC-123\",\"token\":\"very-secret\",\"value\":10}";
        service.saveApiResponseAuditSample(
                "MOCK",
                "quote-api",
                OffsetDateTime.now().minusSeconds(1),
                OffsetDateTime.now(),
                200,
                10,
                payload,
                true,
                null);

        ArgumentCaptor<ApiResponseAuditEntity> captor = ArgumentCaptor.forClass(ApiResponseAuditEntity.class);
        verify(apiResponseAuditRepository).save(captor.capture());
        ApiResponseAuditEntity saved = captor.getValue();
        assertThat(saved.getSamplePayloadJson()).contains("\"api_key\":\"***\"");
        assertThat(saved.getSamplePayloadJson()).contains("\"token\":\"***\"");
        assertThat(saved.getSamplePayloadJson()).doesNotContain("very-secret");
    }
}

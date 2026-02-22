package com.wangbyul.gnd.api.service.signal;

import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.service.signal.model.PressureDetectionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * PressureDetectionService 정책 테스트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
class PressureDetectionServiceTest {

    private MarketPriceBarRepository marketPriceBarRepository;
    private PressureDetectionService pressureDetectionService;

    @BeforeEach
    void setUp() {
        marketPriceBarRepository = Mockito.mock(MarketPriceBarRepository.class);
        SignalPolicyProperties props = new SignalPolicyProperties();
        props.setPressureWindowBars(10);
        props.setVolumeWindowBars(10);
        props.setVolumeSameTolerancePct(BigDecimal.valueOf(5d));
        props.setPressureDetectionThreshold(BigDecimal.valueOf(0.7d));
        pressureDetectionService = new PressureDetectionService(props, marketPriceBarRepository, new ObjectMapper());
    }

    @Test
    void whenNotEnoughBarsThenNeutral() {
        Mockito.when(marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc("A", "H1"))
                .thenReturn(List.of());
        Mockito.when(marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc("A", "D1"))
                .thenReturn(List.of());
        PressureDetectionResult result = pressureDetectionService.analyze("A");
        Assertions.assertFalse(result.sellPressureDetected());
        Assertions.assertFalse(result.buyPressureDetected());
    }

    @Test
    void continuousSellAndVolumeDifferentThenNegativeTrue() {
        List<MarketPriceBarEntity> bars = buildBars(20, true, false, 1000d, 1500d);
        Mockito.when(marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc("A", "H1"))
                .thenReturn(bars);
        PressureDetectionResult result = pressureDetectionService.analyze("A");
        Assertions.assertTrue(result.sellPressureDetected());
        Assertions.assertTrue(result.sellPressureNegative());
    }

    @Test
    void continuousSellButVolumeSameThenNegativeFalse() {
        List<MarketPriceBarEntity> bars = buildBars(20, true, false, 1000d, 1010d);
        Mockito.when(marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc("A", "H1"))
                .thenReturn(bars);
        PressureDetectionResult result = pressureDetectionService.analyze("A");
        Assertions.assertTrue(result.sellPressureDetected());
        Assertions.assertFalse(result.sellPressureNegative());
        Assertions.assertTrue(result.volumeRegimeSame());
    }

    @Test
    void continuousBuyAndVolumeDifferentThenPositiveTrue() {
        List<MarketPriceBarEntity> bars = buildBars(20, false, true, 1000d, 1500d);
        Mockito.when(marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc("A", "H1"))
                .thenReturn(bars);
        PressureDetectionResult result = pressureDetectionService.analyze("A");
        Assertions.assertTrue(result.buyPressureDetected());
        Assertions.assertTrue(result.buyPressurePositive());
    }

    @Test
    void continuousBuyButVolumeSameThenPositiveFalse() {
        List<MarketPriceBarEntity> bars = buildBars(20, false, true, 1000d, 1004d);
        Mockito.when(marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc("A", "H1"))
                .thenReturn(bars);
        PressureDetectionResult result = pressureDetectionService.analyze("A");
        Assertions.assertTrue(result.buyPressureDetected());
        Assertions.assertFalse(result.buyPressurePositive());
    }

    @Test
    void mixedCandlesThenNoPressure() {
        List<MarketPriceBarEntity> bars = buildMixedBars(20);
        Mockito.when(marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc("A", "H1"))
                .thenReturn(bars);
        PressureDetectionResult result = pressureDetectionService.analyze("A");
        Assertions.assertFalse(result.sellPressureDetected());
        Assertions.assertFalse(result.buyPressureDetected());
    }

    @Test
    void fallbackToDailyBarsIfHourlyInsufficient() {
        Mockito.when(marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc("A", "H1"))
                .thenReturn(List.of());
        Mockito.when(marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc("A", "D1"))
                .thenReturn(buildBars(20, true, false, 1000d, 1400d));
        PressureDetectionResult result = pressureDetectionService.analyze("A");
        Assertions.assertTrue(result.sellPressureDetected());
    }

    private List<MarketPriceBarEntity> buildBars(int count, boolean sell, boolean buy, double recentVolume, double previousVolume) {
        List<MarketPriceBarEntity> bars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MarketPriceBarEntity bar = new MarketPriceBarEntity();
            bar.setAssetCode("A");
            bar.setTimeframe("H1");
            bar.setBarTime(OffsetDateTime.now().minusHours(i));
            if (sell) {
                bar.setOpenPrice(BigDecimal.valueOf(100));
                bar.setClosePrice(BigDecimal.valueOf(95));
            } else if (buy) {
                bar.setOpenPrice(BigDecimal.valueOf(95));
                bar.setClosePrice(BigDecimal.valueOf(100));
            } else {
                bar.setOpenPrice(BigDecimal.valueOf(100));
                bar.setClosePrice(BigDecimal.valueOf(100));
            }
            bar.setHighPrice(BigDecimal.valueOf(101));
            bar.setLowPrice(BigDecimal.valueOf(94));
            bar.setVolume(BigDecimal.valueOf(i < 10 ? recentVolume : previousVolume));
            bars.add(bar);
        }
        return bars;
    }

    private List<MarketPriceBarEntity> buildMixedBars(int count) {
        List<MarketPriceBarEntity> bars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MarketPriceBarEntity bar = new MarketPriceBarEntity();
            bar.setAssetCode("A");
            bar.setTimeframe("H1");
            bar.setBarTime(OffsetDateTime.now().minusHours(i));
            if (i % 2 == 0) {
                bar.setOpenPrice(BigDecimal.valueOf(100));
                bar.setClosePrice(BigDecimal.valueOf(101));
            } else {
                bar.setOpenPrice(BigDecimal.valueOf(101));
                bar.setClosePrice(BigDecimal.valueOf(100));
            }
            bar.setHighPrice(BigDecimal.valueOf(102));
            bar.setLowPrice(BigDecimal.valueOf(99));
            bar.setVolume(BigDecimal.valueOf(1000));
            bars.add(bar);
        }
        return bars;
    }
}

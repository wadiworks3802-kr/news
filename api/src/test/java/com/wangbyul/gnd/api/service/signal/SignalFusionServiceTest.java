package com.wangbyul.gnd.api.service.signal;

import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.service.signal.model.ChartPositionResult;
import com.wangbyul.gnd.api.service.signal.model.FusionSignalResult;
import com.wangbyul.gnd.api.service.signal.model.MarketTrendSignalResult;
import com.wangbyul.gnd.api.service.signal.model.PressureDetectionResult;
import com.wangbyul.gnd.api.service.signal.model.ScalpSignalResult;
import com.wangbyul.gnd.api.service.signal.model.WeeklyContextResult;
import com.wangbyul.gnd.core.domain.MarketRegimeType;
import com.wangbyul.gnd.core.domain.SignalActionType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SignalFusionService 정책 테스트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
class SignalFusionServiceTest {

    private SignalFusionService signalFusionService;

    @BeforeEach
    void setUp() {
        SignalPolicyProperties props = new SignalPolicyProperties();
        props.setNewsWindowsMinutes(List.of(10, 30, 60));
        props.setGoodNewsThreshold(BigDecimal.valueOf(0.6d));
        props.setBadNewsThreshold(BigDecimal.valueOf(0.6d));
        props.setFusionChartGoodThreshold(BigDecimal.valueOf(0.55d));
        props.setFusionChartWeakThreshold(BigDecimal.valueOf(0.45d));
        props.setFusionHighConfidenceThreshold(BigDecimal.valueOf(0.62d));
        signalFusionService = new SignalFusionService(props);
    }

    @Test
    void buyLockActiveThenActionIsBuyLock() {
        FusionSignalResult result = signalFusionService.fuse(
                scalp(0.8, 0.2, 0.8),
                trend(0.4),
                chart(0.8),
                pressure(true, false),
                weekly(0.2),
                true);
        Assertions.assertEquals(SignalActionType.BUY_LOCK, result.action());
    }

    @Test
    void newsAndChartAndBuyPressureThenBuyCandidate() {
        FusionSignalResult result = signalFusionService.fuse(
                scalp(0.78, 0.12, 0.78),
                trend(0.4),
                chart(0.82),
                pressure(false, true),
                weekly(0.3),
                false);
        Assertions.assertEquals(SignalActionType.BUY_CANDIDATE, result.action());
    }

    @Test
    void badNewsAndWeakChartAndSellPressureThenSellCandidate() {
        FusionSignalResult result = signalFusionService.fuse(
                scalp(0.15, 0.81, 1.0),
                trend(1.0),
                chart(0.35),
                pressure(true, false),
                weekly(1.0),
                false);
        Assertions.assertEquals(SignalActionType.SELL_CANDIDATE, result.action());
    }

    @Test
    void newsOnlyGoodThenWatch() {
        FusionSignalResult result = signalFusionService.fuse(
                scalp(0.7, 0.2, 0.7),
                trend(0.1),
                chart(0.45),
                pressure(false, false),
                weekly(0.1),
                false);
        Assertions.assertEquals(SignalActionType.WATCH, result.action());
    }

    @Test
    void chartOnlyGoodThenWatch() {
        FusionSignalResult result = signalFusionService.fuse(
                scalp(0.45, 0.42, 0.4),
                trend(0.2),
                chart(0.78),
                pressure(false, false),
                weekly(0.1),
                false);
        Assertions.assertEquals(SignalActionType.WATCH, result.action());
    }

    @Test
    void neutralThenHold() {
        FusionSignalResult result = signalFusionService.fuse(
                scalp(0.51, 0.49, 0.4),
                trend(0),
                chart(0.5),
                pressure(false, false),
                weekly(0),
                false);
        Assertions.assertEquals(SignalActionType.HOLD, result.action());
    }

    @Test
    void combinedConfidenceIsBounded() {
        FusionSignalResult result = signalFusionService.fuse(
                scalp(1.0, 0.0, 1.0),
                trend(1.0),
                chart(1.0),
                pressure(false, true),
                weekly(1.0),
                false);
        Assertions.assertTrue(result.combinedConfidence().compareTo(BigDecimal.ONE) <= 0);
        Assertions.assertTrue(result.combinedConfidence().compareTo(BigDecimal.ZERO) >= 0);
    }

    @Test
    void lowConfidenceCannotBecomeBuyCandidate() {
        FusionSignalResult result = signalFusionService.fuse(
                scalp(0.7, 0.1, 0.2),
                trend(0.1),
                chart(0.57),
                pressure(false, true),
                weekly(0.05),
                false);
        Assertions.assertNotEquals(SignalActionType.BUY_CANDIDATE, result.action());
    }

    @Test
    void weeklyContextPropagated() {
        FusionSignalResult result = signalFusionService.fuse(
                scalp(0.55, 0.45, 0.5),
                trend(0.0),
                chart(0.5),
                pressure(false, false),
                weekly(0.33),
                false);
        Assertions.assertEquals(BigDecimal.valueOf(0.33d).setScale(4), result.weeklyContextScore().setScale(4));
    }

    private ScalpSignalResult scalp(double good, double bad, double conf) {
        return new ScalpSignalResult(
                BigDecimal.valueOf(good - bad),
                BigDecimal.valueOf(good),
                BigDecimal.valueOf(bad),
                BigDecimal.valueOf(conf),
                12,
                "{}");
    }

    private MarketTrendSignalResult trend(double swing) {
        return new MarketTrendSignalResult(MarketRegimeType.MIXED, BigDecimal.ZERO, BigDecimal.valueOf(swing));
    }

    private ChartPositionResult chart(double conf) {
        return new ChartPositionResult(
                BigDecimal.ZERO,
                BigDecimal.valueOf(conf),
                conf >= 0.6d,
                conf <= 0.4d,
                false,
                false,
                false,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                false,
                0,
                BigDecimal.ZERO,
                "",
                "",
                "{}");
    }

    private PressureDetectionResult pressure(boolean sellNegative, boolean buyPositive) {
        return new PressureDetectionResult(
                sellNegative,
                sellNegative,
                buyPositive,
                buyPositive,
                false,
                20,
                BigDecimal.valueOf(sellNegative ? 0.8d : 0.2d),
                BigDecimal.valueOf(buyPositive ? 0.8d : 0.2d),
                BigDecimal.valueOf(12d),
                BigDecimal.valueOf(0.7d),
                "{}");
    }

    private WeeklyContextResult weekly(double score) {
        return new WeeklyContextResult(
                10,
                BigDecimal.valueOf(0.6d),
                BigDecimal.valueOf(0.4d),
                BigDecimal.valueOf(score),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.valueOf(score));
    }
}

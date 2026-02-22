package com.wangbyul.gnd.api.service.signal;

import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.service.signal.model.ChartPositionResult;
import com.wangbyul.gnd.api.service.signal.model.FusionSignalResult;
import com.wangbyul.gnd.api.service.signal.model.MarketTrendSignalResult;
import com.wangbyul.gnd.api.service.signal.model.PressureDetectionResult;
import com.wangbyul.gnd.api.service.signal.model.ScalpSignalResult;
import com.wangbyul.gnd.api.service.signal.model.WeeklyContextResult;
import com.wangbyul.gnd.core.domain.SignalActionType;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/**
 * 엔진별 결과를 최종 액션으로 퓨전하는 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class SignalFusionService {

    private final SignalPolicyProperties signalPolicyProperties;

    public SignalFusionService(SignalPolicyProperties signalPolicyProperties) {
        this.signalPolicyProperties = signalPolicyProperties;
    }

    public FusionSignalResult fuse(
            ScalpSignalResult scalp,
            MarketTrendSignalResult marketTrend,
            ChartPositionResult chart,
            PressureDetectionResult pressure,
            WeeklyContextResult weeklyContext,
            boolean buyLockActive) {
        if (buyLockActive) {
            return new FusionSignalResult(SignalActionType.BUY_LOCK, BigDecimal.ZERO.setScale(4), weeklyContext.weeklyContextScore());
        }

        BigDecimal combined = computeCombinedConfidence(
                scalp.newsConfidence(),
                chart.chartConfidence(),
                weeklyContext.weeklyContextScore(),
                marketTrend.swingSignalScore());

        SignalActionType action = resolveAction(scalp, chart, pressure, combined);
        return new FusionSignalResult(action, combined, weeklyContext.weeklyContextScore());
    }

    private BigDecimal computeCombinedConfidence(
            BigDecimal newsConfidence,
            BigDecimal chartConfidence,
            BigDecimal weeklyContextScore,
            BigDecimal swingSignalScore) {
        BigDecimal weeklyNormalized = SignalMath.clamp01(BigDecimal.valueOf(0.5d).add(weeklyContextScore.multiply(BigDecimal.valueOf(0.5d))));
        BigDecimal swingNormalized = SignalMath.clamp01(BigDecimal.valueOf(0.5d).add(swingSignalScore.multiply(BigDecimal.valueOf(0.5d))));
        BigDecimal combined = newsConfidence.multiply(BigDecimal.valueOf(0.35d))
                .add(chartConfidence.multiply(BigDecimal.valueOf(0.35d)))
                .add(weeklyNormalized.multiply(BigDecimal.valueOf(0.20d)))
                .add(swingNormalized.multiply(BigDecimal.valueOf(0.10d)));
        return SignalMath.clamp01(combined);
    }

    private SignalActionType resolveAction(
            ScalpSignalResult scalp,
            ChartPositionResult chart,
            PressureDetectionResult pressure,
            BigDecimal combined) {
        boolean newsGood = scalp.goodNewsProbability().compareTo(signalPolicyProperties.getGoodNewsThreshold()) >= 0;
        boolean newsBad = scalp.badNewsProbability().compareTo(signalPolicyProperties.getBadNewsThreshold()) >= 0;
        boolean chartGood = chart.chartConfidence().compareTo(signalPolicyProperties.getFusionChartGoodThreshold()) >= 0;
        boolean chartWeak = chart.chartConfidence().compareTo(signalPolicyProperties.getFusionChartWeakThreshold()) <= 0;
        boolean highConfidence = combined.compareTo(signalPolicyProperties.getFusionHighConfidenceThreshold()) >= 0;
        boolean pressureNeutralizedByVolumeSame = pressure.volumeRegimeSame()
                && (pressure.buyPressureDetected() || pressure.sellPressureDetected())
                && !pressure.buyPressurePositive()
                && !pressure.sellPressureNegative();

        // 사용자 규칙: 거래량 동일 구간의 연속 매수/매도는 자동 호재/악재 단정 금지
        if (pressureNeutralizedByVolumeSame) {
            return SignalActionType.WATCH;
        }

        // 정책: 뉴스와 차트가 둘 다 있어야 적극 BUY 후보 생성
        if (newsGood && chartGood && pressure.buyPressurePositive() && highConfidence) {
            return SignalActionType.BUY_CANDIDATE;
        }

        if (newsBad && chartWeak && pressure.sellPressureNegative() && highConfidence) {
            return SignalActionType.SELL_CANDIDATE;
        }

        // 뉴스만 좋고 차트가 불량이면 과열 방지를 위해 WATCH
        if (newsGood && !chartGood) {
            return SignalActionType.WATCH;
        }

        // 차트만 좋고 뉴스 부재/중립이면 WATCH
        if (!newsGood && chartGood) {
            return SignalActionType.WATCH;
        }

        return SignalActionType.HOLD;
    }
}

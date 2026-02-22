package com.wangbyul.gnd.api.service.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.RiskPolicyProperties;
import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.service.signal.model.ChartPositionResult;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.PaperTradePositionEntity;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.PaperTradePositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 차트 대응/평단가 정책 엔진.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class ChartPositionStrategyService {

    private final MarketPriceBarRepository marketPriceBarRepository;
    private final PaperTradePositionRepository paperTradePositionRepository;
    private final RiskPolicyProperties riskPolicyProperties;
    private final SignalPolicyProperties signalPolicyProperties;
    private final ObjectMapper objectMapper;

    public ChartPositionStrategyService(
            MarketPriceBarRepository marketPriceBarRepository,
            PaperTradePositionRepository paperTradePositionRepository,
            RiskPolicyProperties riskPolicyProperties,
            SignalPolicyProperties signalPolicyProperties,
            ObjectMapper objectMapper) {
        this.marketPriceBarRepository = marketPriceBarRepository;
        this.paperTradePositionRepository = paperTradePositionRepository;
        this.riskPolicyProperties = riskPolicyProperties;
        this.signalPolicyProperties = signalPolicyProperties;
        this.objectMapper = objectMapper;
    }

    public ChartPositionResult analyze(AssetUniverseEntity asset, BigDecimal badNewsProbability) {
        List<MarketPriceBarEntity> bars = marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(
                asset.getAssetCode(),
                "D1");

        int atrPeriod = Math.max(5, signalPolicyProperties.getChartAtrPeriod());
        int volumeAvgBars = Math.max(5, signalPolicyProperties.getChartVolumeAverageBars());
        int breakoutLookbackBars = Math.max(5, signalPolicyProperties.getChartHighLowLookbackBars());
        int slopeLookbackBars = Math.max(5, signalPolicyProperties.getChartTrendSlopeLookbackBars());
        int minimumBars = Math.max(20, Math.max(atrPeriod + 2, Math.max(volumeAvgBars * 2, breakoutLookbackBars + 2)));
        if (bars.size() < minimumBars) {
            return new ChartPositionResult(
                    BigDecimal.ZERO,
                    BigDecimal.valueOf(0.35d),
                    false,
                    false,
                    false,
                    false,
                    false,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    false,
                    0,
                    BigDecimal.ZERO,
                    "차트 데이터 부족",
                    "bar-data-insufficient",
                    "{\"reason\":\"insufficient-bar-data\"}");
        }

        BigDecimal close = bars.get(0).getClosePrice();
        BigDecimal ma5 = averageClose(bars, 0, 5);
        BigDecimal ma20 = averageClose(bars, 0, 20);
        BigDecimal ma60 = averageClose(bars, 0, Math.min(60, bars.size()));
        BigDecimal ma120 = averageClose(bars, 0, Math.min(120, bars.size()));
        BigDecimal atr = computeAtr(bars, atrPeriod);
        BigDecimal atrPct = SignalMath.safeDivide(atr, close)
                .multiply(BigDecimal.valueOf(100d))
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal volumeRatio = computeVolumeRatio(bars, volumeAvgBars);
        BigDecimal trendSlopePct = computeTrendSlopePct(bars, slopeLookbackBars);
        boolean newHighBreakout = isNewHighBreakout(close, bars, breakoutLookbackBars);
        boolean newLowBreakdown = isNewLowBreakdown(close, bars, breakoutLookbackBars);

        BigDecimal volumeRatioMin = nonNull(signalPolicyProperties.getChartVolumeRatioMin(), BigDecimal.valueOf(1.05d));
        BigDecimal slopeMinPct = nonNull(signalPolicyProperties.getChartTrendSlopeMinPct(), BigDecimal.valueOf(0.15d));
        int longMinHits = Math.max(1, signalPolicyProperties.getChartLongBiasMinRuleHits());
        int shortMinHits = Math.max(1, signalPolicyProperties.getChartShortBiasMinRuleHits());

        int longHits = 0;
        longHits += isGreater(close, ma20) ? 1 : 0;
        longHits += isGreater(ma20, ma60) ? 1 : 0;
        longHits += isGreater(ma60, ma120) ? 1 : 0;
        longHits += volumeRatio.compareTo(volumeRatioMin) >= 0 ? 1 : 0;
        longHits += trendSlopePct.compareTo(slopeMinPct) >= 0 ? 1 : 0;
        longHits += newHighBreakout ? 1 : 0;

        int shortHits = 0;
        shortHits += isLess(close, ma20) ? 1 : 0;
        shortHits += isLess(ma20, ma60) ? 1 : 0;
        shortHits += isLess(ma60, ma120) ? 1 : 0;
        shortHits += volumeRatio.compareTo(volumeRatioMin) >= 0 ? 1 : 0;
        shortHits += trendSlopePct.compareTo(slopeMinPct.negate()) <= 0 ? 1 : 0;
        shortHits += newLowBreakdown ? 1 : 0;

        boolean longBias = longHits >= longMinHits;
        boolean shortBias = shortHits >= shortMinHits;

        BigDecimal breakdownPct = nonNull(signalPolicyProperties.getChartTrendBreakdownPct(), BigDecimal.valueOf(8d))
                .divide(BigDecimal.valueOf(100d), 6, RoundingMode.HALF_UP);
        BigDecimal atrMultiplier = nonNull(signalPolicyProperties.getChartTrendBreakdownAtrMultiplier(), BigDecimal.valueOf(1.2d));
        BigDecimal distanceToMa120 = SignalMath.safeDivide(ma120.subtract(close), ma120).max(BigDecimal.ZERO);
        BigDecimal atrGuard = SignalMath.safeDivide(atr.multiply(atrMultiplier), close);

        boolean trendBreakdownSevere = ma120.compareTo(BigDecimal.ZERO) > 0
                && close.compareTo(ma120) < 0
                && (distanceToMa120.compareTo(breakdownPct) >= 0
                || (newLowBreakdown && distanceToMa120.compareTo(atrGuard) >= 0));

        int totalRules = 6;
        int dominantHits = Math.max(longHits, shortHits);
        BigDecimal chartConfidence = SignalMath.clamp01(
                BigDecimal.valueOf(dominantHits)
                        .divide(BigDecimal.valueOf(totalRules), 6, RoundingMode.HALF_UP)
                        .subtract(trendBreakdownSevere ? BigDecimal.valueOf(0.15d) : BigDecimal.ZERO));
        BigDecimal positionSignal = resolvePositionSignal(longBias, shortBias, longHits, shortHits, totalRules, trendBreakdownSevere);

        PaperTradePositionEntity position = paperTradePositionRepository.findByAssetCode(asset.getAssetCode()).orElse(null);
        int currentStage = position == null ? 0 : Math.max(0, position.getAvgDownStage());

        boolean badNewsDominant = badNewsProbability != null
                && badNewsProbability.compareTo(signalPolicyProperties.getBadNewsThreshold()) >= 0;
        boolean liquidityOkay = asset.getLiquidityScore() == null
                || asset.getLiquidityScore().compareTo(BigDecimal.valueOf(0.25d)) >= 0;
        boolean stageOkay = currentStage < riskPolicyProperties.getBuySplitRules().size();
        boolean chartSupportive = longBias
                || (!shortBias && chartConfidence.compareTo(nonNull(signalPolicyProperties.getChartAvgDownMinConfidence(), BigDecimal.valueOf(0.45d))) >= 0);

        boolean avgDownAllowed = !trendBreakdownSevere && !badNewsDominant && liquidityOkay && stageOkay && chartSupportive;
        BigDecimal nextBuyRatio = avgDownAllowed ? resolveNextBuyRatio(currentStage) : BigDecimal.ZERO;

        String avgDownReason = avgDownAllowed
                ? "차트 롱바이어스 또는 중립 이상/악재 과도 아님/유동성 충족"
                : buildDeniedReason(trendBreakdownSevere, badNewsDominant, liquidityOkay, stageOkay, chartSupportive);
        String riskWarning = trendBreakdownSevere
                ? "장기추세 붕괴 위험"
                : (badNewsDominant ? "최근 악재 우세" : (shortBias ? "차트 숏 바이어스" : "정상"));

        Map<String, Object> chartRuleHits = new LinkedHashMap<>();
        chartRuleHits.put("rule_set", "CHART_RULESET_V1");
        chartRuleHits.put("ma5", ma5);
        chartRuleHits.put("ma20", ma20);
        chartRuleHits.put("ma60", ma60);
        chartRuleHits.put("ma120", ma120);
        chartRuleHits.put("atr", atr);
        chartRuleHits.put("atr_pct", atrPct);
        chartRuleHits.put("volume_ratio", volumeRatio);
        chartRuleHits.put("trend_slope_pct", trendSlopePct);
        chartRuleHits.put("new_high_breakout", newHighBreakout);
        chartRuleHits.put("new_low_breakdown", newLowBreakdown);
        chartRuleHits.put("long_hits", longHits);
        chartRuleHits.put("short_hits", shortHits);
        chartRuleHits.put("long_bias", longBias);
        chartRuleHits.put("short_bias", shortBias);
        chartRuleHits.put("trend_breakdown_severe", trendBreakdownSevere);
        chartRuleHits.put("position_management_signal", positionSignal);
        chartRuleHits.put("chart_confidence", chartConfidence);

        return new ChartPositionResult(
                positionSignal,
                chartConfidence,
                longBias,
                shortBias,
                trendBreakdownSevere,
                newHighBreakout,
                newLowBreakdown,
                atrPct,
                volumeRatio,
                trendSlopePct,
                avgDownAllowed,
                currentStage,
                nextBuyRatio,
                avgDownReason,
                riskWarning,
                toJson(chartRuleHits));
    }

    private BigDecimal resolveNextBuyRatio(int stage) {
        List<Integer> rules = riskPolicyProperties.getBuySplitRules();
        if (stage < 0 || stage >= rules.size()) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(rules.get(stage))
                .divide(BigDecimal.valueOf(100d), 4, RoundingMode.HALF_UP);
    }

    private String buildDeniedReason(
            boolean trendBreakdownSevere,
            boolean badNewsDominant,
            boolean liquidityOkay,
            boolean stageOkay,
            boolean chartSupportive) {
        if (trendBreakdownSevere) {
            return "장기 추세 붕괴 상태";
        }
        if (badNewsDominant) {
            return "최근 1주 악재 확률 과도";
        }
        if (!liquidityOkay) {
            return "유동성 부족";
        }
        if (!stageOkay) {
            return "분할매수 단계 한도 초과";
        }
        if (!chartSupportive) {
            return "차트 지표 기준 미충족";
        }
        return "리스크 한도 초과";
    }

    private BigDecimal averageClose(List<MarketPriceBarEntity> bars, int offset, int count) {
        if (bars.isEmpty() || offset >= bars.size()) {
            return BigDecimal.ZERO;
        }
        int safeCount = Math.min(count, bars.size() - offset);
        if (safeCount <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = offset; i < (offset + safeCount); i++) {
            sum = sum.add(bars.get(i).getClosePrice());
        }
        return sum.divide(BigDecimal.valueOf(safeCount), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal computeAtr(List<MarketPriceBarEntity> bars, int period) {
        int usable = Math.min(period, bars.size() - 1);
        if (usable <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < usable; i++) {
            MarketPriceBarEntity current = bars.get(i);
            MarketPriceBarEntity previous = bars.get(i + 1);
            BigDecimal hl = current.getHighPrice().subtract(current.getLowPrice()).abs();
            BigDecimal hc = current.getHighPrice().subtract(previous.getClosePrice()).abs();
            BigDecimal lc = current.getLowPrice().subtract(previous.getClosePrice()).abs();
            BigDecimal tr = hl.max(hc).max(lc);
            sum = sum.add(tr);
        }
        return sum.divide(BigDecimal.valueOf(usable), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal computeVolumeRatio(List<MarketPriceBarEntity> bars, int window) {
        BigDecimal recent = averageVolume(bars, 0, window);
        BigDecimal previous = averageVolume(bars, window, window);
        return SignalMath.safeDivide(recent, previous);
    }

    private BigDecimal averageVolume(List<MarketPriceBarEntity> bars, int offset, int count) {
        if (bars.isEmpty() || offset >= bars.size()) {
            return BigDecimal.ZERO;
        }
        int safeCount = Math.min(count, bars.size() - offset);
        if (safeCount <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = offset; i < (offset + safeCount); i++) {
            BigDecimal volume = bars.get(i).getVolume() == null ? BigDecimal.ZERO : bars.get(i).getVolume();
            sum = sum.add(volume);
        }
        return sum.divide(BigDecimal.valueOf(safeCount), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal computeTrendSlopePct(List<MarketPriceBarEntity> bars, int slopeLookbackBars) {
        BigDecimal currentMa20 = averageClose(bars, 0, 20);
        BigDecimal previousMa20 = averageClose(bars, slopeLookbackBars, 20);
        if (previousMa20.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentMa20.subtract(previousMa20)
                .multiply(BigDecimal.valueOf(100d))
                .divide(previousMa20, 6, RoundingMode.HALF_UP);
    }

    private boolean isNewHighBreakout(BigDecimal close, List<MarketPriceBarEntity> bars, int lookback) {
        BigDecimal highest = highestHigh(bars, 1, lookback);
        return highest != null && close.compareTo(highest) > 0;
    }

    private boolean isNewLowBreakdown(BigDecimal close, List<MarketPriceBarEntity> bars, int lookback) {
        BigDecimal lowest = lowestLow(bars, 1, lookback);
        return lowest != null && close.compareTo(lowest) < 0;
    }

    private BigDecimal highestHigh(List<MarketPriceBarEntity> bars, int offset, int count) {
        int end = Math.min(bars.size(), offset + count);
        if (offset >= end) {
            return null;
        }
        BigDecimal highest = null;
        for (int i = offset; i < end; i++) {
            BigDecimal high = bars.get(i).getHighPrice();
            highest = highest == null ? high : highest.max(high);
        }
        return highest;
    }

    private BigDecimal lowestLow(List<MarketPriceBarEntity> bars, int offset, int count) {
        int end = Math.min(bars.size(), offset + count);
        if (offset >= end) {
            return null;
        }
        BigDecimal lowest = null;
        for (int i = offset; i < end; i++) {
            BigDecimal low = bars.get(i).getLowPrice();
            lowest = lowest == null ? low : lowest.min(low);
        }
        return lowest;
    }

    private BigDecimal resolvePositionSignal(
            boolean longBias,
            boolean shortBias,
            int longHits,
            int shortHits,
            int totalRules,
            boolean trendBreakdownSevere) {
        BigDecimal bias = BigDecimal.ZERO;
        if (longBias && !shortBias) {
            bias = BigDecimal.valueOf(longHits)
                    .divide(BigDecimal.valueOf(totalRules), 6, RoundingMode.HALF_UP);
        } else if (shortBias && !longBias) {
            bias = BigDecimal.valueOf(shortHits)
                    .divide(BigDecimal.valueOf(totalRules), 6, RoundingMode.HALF_UP)
                    .negate();
        }
        if (trendBreakdownSevere) {
            bias = bias.subtract(BigDecimal.valueOf(0.2d));
        }
        return SignalMath.clampScore(bias);
    }

    private boolean isGreater(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) > 0;
    }

    private boolean isLess(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) < 0;
    }

    private BigDecimal nonNull(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}

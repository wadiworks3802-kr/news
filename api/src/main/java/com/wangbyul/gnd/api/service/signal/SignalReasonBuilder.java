package com.wangbyul.gnd.api.service.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.service.signal.model.ChartPositionResult;
import com.wangbyul.gnd.api.service.signal.model.DiscoveryResult;
import com.wangbyul.gnd.api.service.signal.model.FusionSignalResult;
import com.wangbyul.gnd.api.service.signal.model.MarketTrendSignalResult;
import com.wangbyul.gnd.api.service.signal.model.PressureDetectionResult;
import com.wangbyul.gnd.api.service.signal.model.RiskDecision;
import com.wangbyul.gnd.api.service.signal.model.ScalpSignalResult;
import com.wangbyul.gnd.api.service.signal.model.WeeklyContextResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 설명 가능한 시그널 reason_json 생성기.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class SignalReasonBuilder {

    private final ObjectMapper objectMapper;

    public SignalReasonBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String build(
            ScalpSignalResult scalp,
            MarketTrendSignalResult trend,
            ChartPositionResult chart,
            DiscoveryResult discovery,
            PressureDetectionResult pressure,
            WeeklyContextResult weekly,
            FusionSignalResult fusion,
            RiskDecision riskDecision) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("scalp", Map.of(
                    "score", scalp.scalpSignalScore(),
                    "good_news_probability", scalp.goodNewsProbability(),
                    "bad_news_probability", scalp.badNewsProbability(),
                    "matched_news_count", scalp.matchedNewsCount(),
                    "probability_reason_breakdown", parseJson(scalp.probabilityReasonBreakdownJson())));
            root.put("market_trend", Map.of(
                    "market_regime", trend.marketRegime(),
                    "theme_strength_score", trend.themeStrengthScore(),
                    "swing_signal_score", trend.swingSignalScore()));
            root.put("chart_position", Map.ofEntries(
                    Map.entry("position_management_signal", chart.positionManagementSignal()),
                    Map.entry("chart_confidence", chart.chartConfidence()),
                    Map.entry("long_bias", chart.longBias()),
                    Map.entry("short_bias", chart.shortBias()),
                    Map.entry("trend_breakdown_severe", chart.trendBreakdownSevere()),
                    Map.entry("new_high_breakout", chart.newHighBreakout()),
                    Map.entry("new_low_breakdown", chart.newLowBreakdown()),
                    Map.entry("atr_pct", chart.atrPct()),
                    Map.entry("volume_ratio", chart.volumeRatio()),
                    Map.entry("trend_slope_pct", chart.trendSlopePct()),
                    Map.entry("avg_down_allowed", chart.avgDownAllowed()),
                    Map.entry("avg_down_stage", chart.avgDownStage()),
                    Map.entry("avg_down_next_buy_ratio", chart.avgDownNextBuyRatio()),
                    Map.entry("risk_warning", chart.riskWarning()),
                    Map.entry("rule_hits", parseJson(chart.chartRuleHitsJson()))));
            root.put("discovery", Map.of(
                    "discovery_score", discovery.discoveryScore(),
                    "candidate_rank", discovery.candidateRank()));
            root.put("pressure", Map.of(
                    "sell_pressure_detected", pressure.sellPressureDetected(),
                    "sell_pressure_is_negative", pressure.sellPressureNegative(),
                    "buy_pressure_detected", pressure.buyPressureDetected(),
                    "buy_pressure_is_positive", pressure.buyPressurePositive(),
                    "volume_regime_same", pressure.volumeRegimeSame(),
                    "sell_pressure_ratio", pressure.sellPressureRatio(),
                    "buy_pressure_ratio", pressure.buyPressureRatio(),
                    "volume_diff_pct", pressure.volumeDiffPct(),
                    "threshold", pressure.pressureThreshold(),
                    "pressure_reason", parseJson(pressure.pressureReasonJson())));
            root.put("weekly_context", Map.of(
                    "news_count_7d", weekly.newsCount7d(),
                    "positive_news_ratio", weekly.positiveNewsRatio(),
                    "negative_news_ratio", weekly.negativeNewsRatio(),
                    "weekly_context_score", weekly.weeklyContextScore()));
            root.put("fusion", Map.of(
                    "action", fusion.action(),
                    "combined_confidence", fusion.combinedConfidence()));
            root.put("risk", Map.of(
                    "allowed", riskDecision.allowed(),
                    "final_action", riskDecision.finalAction(),
                    "risk_checks", riskDecision.riskChecks(),
                    "blocked_reason", riskDecision.blockedReason()));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":\"reason-json-build-failed\"}";
        }
    }

    private Object parseJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception ignored) {
            return Map.of("raw", rawJson);
        }
    }
}

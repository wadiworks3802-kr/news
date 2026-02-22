package com.wangbyul.gnd.api.service.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.service.signal.model.PressureDetectionResult;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 연속 매수/매도 + 거래량 동일 판정 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class PressureDetectionService {

    private final SignalPolicyProperties signalPolicyProperties;
    private final MarketPriceBarRepository marketPriceBarRepository;
    private final ObjectMapper objectMapper;

    public PressureDetectionService(
            SignalPolicyProperties signalPolicyProperties,
            MarketPriceBarRepository marketPriceBarRepository,
            ObjectMapper objectMapper) {
        this.signalPolicyProperties = signalPolicyProperties;
        this.marketPriceBarRepository = marketPriceBarRepository;
        this.objectMapper = objectMapper;
    }

    public PressureDetectionResult analyze(String assetCode) {
        int window = Math.max(5, signalPolicyProperties.getPressureWindowBars());
        int volumeWindow = Math.max(window, signalPolicyProperties.getVolumeWindowBars());
        BigDecimal pressureThreshold = normalizeRate(signalPolicyProperties.getPressureDetectionThreshold(), BigDecimal.valueOf(0.70d));

        List<MarketPriceBarEntity> bars = marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(assetCode, "H1");
        if (bars.size() < window * 2) {
            bars = marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(assetCode, "D1");
        }

        if (bars.size() < window * 2) {
            return new PressureDetectionResult(
                    false,
                    false,
                    false,
                    false,
                    false,
                    window,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    pressureThreshold,
                    "{\"reason\":\"insufficient-bar-data\"}");
        }

        int sellBars = 0;
        int buyBars = 0;
        for (int i = 0; i < window; i++) {
            MarketPriceBarEntity bar = bars.get(i);
            if (bar.getClosePrice().compareTo(bar.getOpenPrice()) < 0) {
                sellBars++;
            }
            if (bar.getClosePrice().compareTo(bar.getOpenPrice()) > 0) {
                buyBars++;
            }
        }

        BigDecimal sellPressureRatio = BigDecimal.valueOf(sellBars)
                .divide(BigDecimal.valueOf(window), 6, RoundingMode.HALF_UP);
        BigDecimal buyPressureRatio = BigDecimal.valueOf(buyBars)
                .divide(BigDecimal.valueOf(window), 6, RoundingMode.HALF_UP);

        boolean sellDetected = sellPressureRatio.compareTo(pressureThreshold) >= 0;
        boolean buyDetected = buyPressureRatio.compareTo(pressureThreshold) >= 0;

        BigDecimal recentAvgVolume = avgVolume(bars, 0, volumeWindow);
        BigDecimal previousAvgVolume = avgVolume(bars, volumeWindow, volumeWindow);
        BigDecimal diffPct = BigDecimal.ZERO;
        if (previousAvgVolume.compareTo(BigDecimal.ZERO) > 0) {
            diffPct = recentAvgVolume.subtract(previousAvgVolume)
                    .abs()
                    .multiply(BigDecimal.valueOf(100d))
                    .divide(previousAvgVolume, 6, RoundingMode.HALF_UP);
        }

        BigDecimal tolerance = signalPolicyProperties.getVolumeSameTolerancePct() == null
                ? BigDecimal.valueOf(5d)
                : signalPolicyProperties.getVolumeSameTolerancePct();
        boolean volumeRegimeSame = diffPct.compareTo(tolerance) <= 0;

        boolean sellNegative = sellDetected && !volumeRegimeSame;
        boolean buyPositive = buyDetected && !volumeRegimeSame;

        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("rule_set", "PRESSURE_RULESET_V1");
        reason.put("window_bars", window);
        reason.put("volume_window_bars", volumeWindow);
        reason.put("pressure_threshold", pressureThreshold);
        reason.put("sell_bars", sellBars);
        reason.put("buy_bars", buyBars);
        reason.put("sell_pressure_ratio", sellPressureRatio);
        reason.put("buy_pressure_ratio", buyPressureRatio);
        reason.put("volume_same_tolerance_pct", tolerance);
        reason.put("volume_diff_pct", diffPct);
        reason.put("volume_regime_same", volumeRegimeSame);
        reason.put("sell_pressure_detected", sellDetected);
        reason.put("buy_pressure_detected", buyDetected);
        reason.put("sell_pressure_is_negative", sellNegative);
        reason.put("buy_pressure_is_positive", buyPositive);

        return new PressureDetectionResult(
                sellDetected,
                sellNegative,
                buyDetected,
                buyPositive,
                volumeRegimeSame,
                window,
                sellPressureRatio,
                buyPressureRatio,
                diffPct,
                pressureThreshold,
                toJson(reason));
    }

    private BigDecimal avgVolume(List<MarketPriceBarEntity> bars, int offset, int count) {
        if (bars.size() <= offset) {
            return BigDecimal.ZERO;
        }
        int end = Math.min(bars.size(), offset + count);
        if (end <= offset) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = offset; i < end; i++) {
            sum = sum.add(bars.get(i).getVolume() == null ? BigDecimal.ZERO : bars.get(i).getVolume());
        }
        return sum.divide(BigDecimal.valueOf(end - offset), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeRate(BigDecimal value, BigDecimal fallback) {
        if (value == null) {
            return fallback;
        }
        return SignalMath.clamp01(value);
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}

package com.wangbyul.gnd.api.service.signal.panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.service.signal.SignalMath;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.domain.UniverseLayerType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;

/**
 * 전략 패널 공통 평가 기능 추상 클래스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public abstract class AbstractSignalPanelStrategyService {

    protected final ObjectMapper objectMapper;

    @Value("${app.market.quality.quote-stale-warning-minutes:180}")
    private int quoteStaleWarningMinutes;

    protected AbstractSignalPanelStrategyService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public abstract PanelStrategyEvaluation evaluate(TradingSignalEntity signal, AssetUniverseEntity asset);

    public abstract String strategyKey();

    public abstract String signalWindow();

    public abstract List<SignalActionType> allowedActions();

    protected abstract BigDecimal layerBonus(UniverseLayerType layer);

    protected BigDecimal scale(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    protected BigDecimal clamp01(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return SignalMath.clamp01(value).setScale(4, RoundingMode.HALF_UP);
    }

    protected boolean isBlocked(TradingSignalEntity signal) {
        return signal != null && signal.getBlockedReason() != null && !signal.getBlockedReason().isBlank();
    }

    protected boolean isQuoteStale(AssetUniverseEntity asset) {
        if (asset == null || asset.getLastQuoteReceivedAt() == null) {
            return true;
        }
        long age = ChronoUnit.MINUTES.between(asset.getLastQuoteReceivedAt(), OffsetDateTime.now());
        return age > Math.max(30, quoteStaleWarningMinutes);
    }

    protected boolean isTradeDisabled(AssetUniverseEntity asset) {
        return asset == null || !Boolean.TRUE.equals(asset.getIsTradeEnabled());
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> parseJsonMap(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = objectMapper.readValue(rawJson, Map.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return result;
            }
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<>();
    }

    protected String textField(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    protected boolean boolField(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return false;
        }
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }
}

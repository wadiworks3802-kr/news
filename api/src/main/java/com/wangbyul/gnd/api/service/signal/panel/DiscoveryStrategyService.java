package com.wangbyul.gnd.api.service.signal.panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.domain.UniverseLayerType;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 장기 발굴 전략 패널 평가/정렬 정책 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class DiscoveryStrategyService extends AbstractSignalPanelStrategyService {

    public DiscoveryStrategyService(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public PanelStrategyEvaluation evaluate(TradingSignalEntity signal, AssetUniverseEntity asset) {
        BigDecimal score = clamp01(scale(signal.getDiscoveryScore()));
        boolean blocked = isBlocked(signal);
        boolean tradeDisabled = isTradeDisabled(asset);
        boolean quoteStale = isQuoteStale(asset);
        boolean qualityDegraded = tradeDisabled || quoteStale;
        Map<String, Object> reasonRoot = parseJsonMap(signal.getReasonJson());
        Map<String, Object> discovery = nestedMap(reasonRoot, "discovery");
        BigDecimal mentionGrowth = decimalField(discovery, "mention_growth");
        BigDecimal trendTurn = decimalField(discovery, "trend_turn_score");
        BigDecimal volumeShift = decimalField(discovery, "volume_shift_score");
        BigDecimal discoveryScore = scale(signal.getDiscoveryScore());
        BigDecimal combined = scale(signal.getCombinedConfidence());

        String badge;
        String reason;
        String recommendationState;
        if (blocked) {
            badge = "차단";
            reason = "차단사유=" + safeString(signal.getBlockedReason(), "RISK_POLICY")
                    + " · 발굴점수 " + discoveryScore
                    + " · 결합 " + combined;
            recommendationState = "BLOCKED";
        } else if (signal.getAction() == SignalActionType.BUY_CANDIDATE) {
            badge = qualityDegraded ? "품질주의" : "발굴";
            reason = "발굴점수 " + discoveryScore
                    + " · 언급증가 " + mentionGrowth
                    + " · 추세전환 " + trendTurn
                    + " · 거래량변화 " + volumeShift
                    + qualityHint(tradeDisabled, quoteStale);
            recommendationState = qualityDegraded ? "WARN" : "RECOMMEND";
        } else {
            badge = qualityDegraded ? "품질주의" : "탐색";
            reason = "발굴점수 " + discoveryScore
                    + " · 결합 " + combined
                    + " · 언급증가 " + mentionGrowth
                    + " · 추세전환 " + trendTurn
                    + qualityHint(tradeDisabled, quoteStale);
            recommendationState = qualityDegraded ? "WARN" : "WATCH_ONLY";
        }

        return new PanelStrategyEvaluation(
                score,
                layerBonus(asset == null ? null : asset.getUniverseLayer()),
                strategyKey(),
                "발굴/테마 선행 탐색",
                "발굴점수",
                scale(signal.getDiscoveryScore()),
                badge,
                reason,
                recommendationState,
                qualityDegraded,
                "discovery_score>diversity>selection_score");
    }

    private Map<String, Object> nestedMap(Map<String, Object> root, String key) {
        if (root == null || key == null) {
            return Map.of();
        }
        Object value = root.get(key);
        if (value instanceof Map<?, ?> raw) {
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private BigDecimal decimalField(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return BigDecimal.ZERO;
        }
        Object value = map.get(key);
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value)).setScale(4, java.math.RoundingMode.HALF_UP);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private String qualityHint(boolean tradeDisabled, boolean quoteStale) {
        if (!tradeDisabled && !quoteStale) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" · 품질경고(");
        if (tradeDisabled) {
            sb.append("거래비활성");
        }
        if (tradeDisabled && quoteStale) {
            sb.append(",");
        }
        if (quoteStale) {
            sb.append("시세지연");
        }
        sb.append(")");
        return sb.toString();
    }

    private String safeString(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    @Override
    public String strategyKey() {
        return "DISCOVERY";
    }

    @Override
    public String signalWindow() {
        return "6m";
    }

    @Override
    public List<SignalActionType> allowedActions() {
        return List.of(
                SignalActionType.BUY_CANDIDATE,
                SignalActionType.WATCH,
                SignalActionType.HOLD,
                SignalActionType.SELL_CANDIDATE);
    }

    @Override
    protected BigDecimal layerBonus(UniverseLayerType layer) {
        if (layer == null) {
            return BigDecimal.ZERO;
        }
        return switch (layer) {
            case DISCOVERY -> BigDecimal.valueOf(0.10d);
            case THEME_LEADER -> BigDecimal.valueOf(0.04d);
            case WATCHLIST -> BigDecimal.valueOf(0.02d);
            case CORE -> BigDecimal.ZERO;
        };
    }
}

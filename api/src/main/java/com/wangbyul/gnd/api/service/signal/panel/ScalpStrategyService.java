package com.wangbyul.gnd.api.service.signal.panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.domain.UniverseLayerType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 단타 전략 패널 평가/정렬 정책 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class ScalpStrategyService extends AbstractSignalPanelStrategyService {

    public ScalpStrategyService(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public PanelStrategyEvaluation evaluate(TradingSignalEntity signal, AssetUniverseEntity asset) {
        BigDecimal score = clamp01(scale(signal.getScalpSignalScore()).add(BigDecimal.valueOf(0.5d)));
        Map<String, Object> breakdown = parseJsonMap(signal.getProbabilityReasonBreakdownJson());
        String dataState = textField(breakdown, "data_state");
        String analysisState = textField(breakdown, "analysis_state");
        int mappedNewsCount = intField(breakdown, "mapped_news_count");
        int eligibleNewsCount = intField(breakdown, "eligible_news_count");
        BigDecimal newsConfidence = decimalField(breakdown, "news_confidence");
        BigDecimal good = scale(signal.getGoodNewsProbability());
        BigDecimal bad = scale(signal.getBadNewsProbability());
        boolean blocked = isBlocked(signal);
        boolean tradeDisabled = isTradeDisabled(asset);
        boolean quoteStale = isQuoteStale(asset);
        boolean qualityDegraded = tradeDisabled || quoteStale
                || "NO_MATCHED_NEWS".equals(dataState) || "INSUFFICIENT_DATA".equals(dataState);

        String badge;
        String reason;
        String recommendationState;
        if (blocked) {
            badge = "차단";
            reason = "차단사유=" + safeString(signal.getBlockedReason(), "RISK_POLICY")
                    + " · 단타점수 " + scale(signal.getScalpSignalScore())
                    + " · 결합 " + scale(signal.getCombinedConfidence());
            recommendationState = "BLOCKED";
        } else if ("NO_MATCHED_NEWS".equals(dataState) || "INSUFFICIENT_DATA".equals(dataState)) {
            badge = "데이터부족";
            reason = "매핑 " + mappedNewsCount + "건 / 유효 " + eligibleNewsCount + "건 · "
                    + safeString(dataState, "INSUFFICIENT_DATA")
                    + (!safeString(analysisState, "").isBlank() ? (" · " + analysisState) : "");
            recommendationState = "DATA_GAP";
        } else if (signal.getAction() == SignalActionType.BUY_CANDIDATE || signal.getAction() == SignalActionType.SELL_CANDIDATE) {
            badge = qualityDegraded ? "품질주의" : "추천";
            reason = "호/악 " + good + "/" + bad
                    + " · 뉴스신뢰 " + scale(newsConfidence)
                    + " · 결합 " + scale(signal.getCombinedConfidence())
                    + qualityHint(tradeDisabled, quoteStale);
            recommendationState = qualityDegraded ? "WARN" : "RECOMMEND";
        } else {
            badge = qualityDegraded ? "품질주의" : "관찰";
            reason = "단타점수 " + scale(signal.getScalpSignalScore())
                    + " · 호/악 " + good + "/" + bad
                    + " · 결합 " + scale(signal.getCombinedConfidence())
                    + qualityHint(tradeDisabled, quoteStale);
            recommendationState = qualityDegraded ? "WARN" : "WATCH_ONLY";
        }

        BigDecimal primaryMetric = signal.getScalpSignalScore() == null
                ? BigDecimal.ZERO
                : signal.getScalpSignalScore().setScale(4, java.math.RoundingMode.HALF_UP);
        return new PanelStrategyEvaluation(
                score,
                layerBonus(asset == null ? null : asset.getUniverseLayer()),
                strategyKey(),
                "단기 뉴스 이벤트 대응",
                "단타점수",
                primaryMetric,
                badge,
                reason,
                recommendationState,
                qualityDegraded,
                "scalp_score>combined_confidence>generated_at");
    }

    private int intField(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return 0;
        }
        Object value = map.get(key);
        if (value instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value).trim()));
        } catch (Exception ignored) {
            return 0;
        }
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
            return new BigDecimal(String.valueOf(value)).setScale(4, RoundingMode.HALF_UP);
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
        return "SCALP";
    }

    @Override
    public String signalWindow() {
        return "1h";
    }

    @Override
    public List<SignalActionType> allowedActions() {
        return List.of(SignalActionType.BUY_CANDIDATE, SignalActionType.SELL_CANDIDATE, SignalActionType.WATCH);
    }

    @Override
    protected BigDecimal layerBonus(UniverseLayerType layer) {
        if (layer == null) {
            return BigDecimal.ZERO;
        }
        return switch (layer) {
            case THEME_LEADER -> BigDecimal.valueOf(0.08d);
            case WATCHLIST -> BigDecimal.valueOf(0.06d);
            case CORE -> BigDecimal.valueOf(0.03d);
            case DISCOVERY -> BigDecimal.ZERO;
        };
    }
}

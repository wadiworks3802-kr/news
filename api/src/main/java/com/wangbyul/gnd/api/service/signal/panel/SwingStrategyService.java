package com.wangbyul.gnd.api.service.signal.panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.domain.UniverseLayerType;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 중기/스윙 전략 패널 평가/정렬 정책 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class SwingStrategyService extends AbstractSignalPanelStrategyService {

    public SwingStrategyService(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public PanelStrategyEvaluation evaluate(TradingSignalEntity signal, AssetUniverseEntity asset) {
        BigDecimal score = clamp01(scale(signal.getSwingSignalScore()).add(BigDecimal.valueOf(0.5d)));
        boolean blocked = isBlocked(signal);
        boolean tradeDisabled = isTradeDisabled(asset);
        boolean quoteStale = isQuoteStale(asset);
        boolean qualityDegraded = tradeDisabled || quoteStale
                || clamp01(scale(signal.getCombinedConfidence())).compareTo(BigDecimal.valueOf(0.35d)) < 0;
        String regime = signal.getMarketRegime() == null ? "MIXED" : signal.getMarketRegime().name().toUpperCase(Locale.ROOT);
        BigDecimal swing = scale(signal.getSwingSignalScore());
        BigDecimal weekly = scale(signal.getWeeklyContextScore());
        BigDecimal combined = scale(signal.getCombinedConfidence());

        String badge;
        String reason;
        String recommendationState;
        if (blocked) {
            badge = "차단";
            reason = "차단사유=" + safeString(signal.getBlockedReason(), "RISK_POLICY")
                    + " · 스윙 " + swing
                    + " · 결합 " + combined;
            recommendationState = "BLOCKED";
        } else if (signal.getAction() == SignalActionType.BUY_CANDIDATE || signal.getAction() == SignalActionType.SELL_CANDIDATE) {
            badge = qualityDegraded ? "품질주의" : "추천";
            reason = "스윙 " + swing + " · 주간 " + weekly + " · 레짐 " + regime + " · 결합 " + combined + qualityHint(tradeDisabled, quoteStale);
            recommendationState = qualityDegraded ? "WARN" : "RECOMMEND";
        } else {
            badge = qualityDegraded ? "품질주의" : "관찰";
            reason = "스윙 " + swing + " · 주간 " + weekly + " · 레짐 " + regime + " · 추세 정렬 대기" + qualityHint(tradeDisabled, quoteStale);
            recommendationState = qualityDegraded ? "WARN" : "WATCH_ONLY";
        }

        return new PanelStrategyEvaluation(
                score,
                layerBonus(asset == null ? null : asset.getUniverseLayer()),
                strategyKey(),
                "중기 추세/주간 컨텍스트",
                "스윙점수",
                scale(signal.getSwingSignalScore()),
                badge,
                reason,
                recommendationState,
                qualityDegraded,
                "swing_score>weekly_context>combined_confidence");
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
        return "SWING";
    }

    @Override
    public String signalWindow() {
        return "1w";
    }

    @Override
    public List<SignalActionType> allowedActions() {
        return List.of(
                SignalActionType.BUY_CANDIDATE,
                SignalActionType.SELL_CANDIDATE,
                SignalActionType.HOLD,
                SignalActionType.WATCH);
    }

    @Override
    protected BigDecimal layerBonus(UniverseLayerType layer) {
        if (layer == null) {
            return BigDecimal.ZERO;
        }
        return switch (layer) {
            case CORE -> BigDecimal.valueOf(0.08d);
            case WATCHLIST -> BigDecimal.valueOf(0.04d);
            case THEME_LEADER -> BigDecimal.valueOf(0.03d);
            case DISCOVERY -> BigDecimal.ZERO;
        };
    }
}

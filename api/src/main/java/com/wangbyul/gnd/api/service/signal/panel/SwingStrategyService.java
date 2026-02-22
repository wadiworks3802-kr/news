package com.wangbyul.gnd.api.service.signal.panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.domain.UniverseLayerType;
import java.math.BigDecimal;
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
        boolean qualityDegraded = isTradeDisabled(asset) || isQuoteStale(asset)
                || clamp01(scale(signal.getCombinedConfidence())).compareTo(BigDecimal.valueOf(0.35d)) < 0;

        String badge;
        String reason;
        String recommendationState;
        if (blocked) {
            badge = "차단";
            reason = "리스크 정책 차단";
            recommendationState = "BLOCKED";
        } else if (signal.getAction() == SignalActionType.BUY_CANDIDATE || signal.getAction() == SignalActionType.SELL_CANDIDATE) {
            badge = qualityDegraded ? "품질주의" : "추천";
            reason = "추세/주간 컨텍스트 기반 중기 후보";
            recommendationState = qualityDegraded ? "WARN" : "RECOMMEND";
        } else {
            badge = qualityDegraded ? "품질주의" : "관찰";
            reason = "중기 추세 정렬 대기";
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

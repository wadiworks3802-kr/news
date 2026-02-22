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
        boolean qualityDegraded = isTradeDisabled(asset) || isQuoteStale(asset);

        String badge;
        String reason;
        String recommendationState;
        if (blocked) {
            badge = "차단";
            reason = "리스크 정책 차단";
            recommendationState = "BLOCKED";
        } else if (signal.getAction() == SignalActionType.BUY_CANDIDATE) {
            badge = qualityDegraded ? "품질주의" : "발굴";
            reason = "장기/테마 선행 발굴 후보";
            recommendationState = qualityDegraded ? "WARN" : "RECOMMEND";
        } else {
            badge = qualityDegraded ? "품질주의" : "탐색";
            reason = "발굴 후보 스크리닝 결과";
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

package com.wangbyul.gnd.api.service.signal.panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.domain.UniverseLayerType;
import java.math.BigDecimal;
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
        boolean blocked = isBlocked(signal);
        boolean qualityDegraded = isTradeDisabled(asset) || isQuoteStale(asset)
                || "NO_MATCHED_NEWS".equals(dataState) || "INSUFFICIENT_DATA".equals(dataState);

        String badge;
        String reason;
        String recommendationState;
        if (blocked) {
            badge = "차단";
            reason = "리스크 정책 또는 시간정렬 검증으로 차단";
            recommendationState = "BLOCKED";
        } else if ("NO_MATCHED_NEWS".equals(dataState) || "INSUFFICIENT_DATA".equals(dataState)) {
            badge = "데이터부족";
            reason = "뉴스-종목 매핑/유효 표본 부족으로 보류";
            recommendationState = "DATA_GAP";
        } else if (signal.getAction() == SignalActionType.BUY_CANDIDATE || signal.getAction() == SignalActionType.SELL_CANDIDATE) {
            badge = qualityDegraded ? "품질주의" : "추천";
            reason = qualityDegraded ? "추천 가능하나 시세/유니버스 품질 저하 주의" : "단기 뉴스 이벤트 대응 후보";
            recommendationState = qualityDegraded ? "WARN" : "RECOMMEND";
        } else {
            badge = qualityDegraded ? "품질주의" : "관찰";
            reason = "단기 이벤트 모니터링 중심";
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

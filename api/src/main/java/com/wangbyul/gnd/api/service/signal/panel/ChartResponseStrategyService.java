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
 * 차트 대응(포지션/압력 확인) 전략 패널 평가 서비스.
 * 거래량 동일 구간에서는 연속 매수/매도만으로 자동 호/악재 단정을 하지 않는 사용자 규칙을 상태 배지에 반영한다.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class ChartResponseStrategyService extends AbstractSignalPanelStrategyService {

    public ChartResponseStrategyService(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public PanelStrategyEvaluation evaluate(TradingSignalEntity signal, AssetUniverseEntity asset) {
        BigDecimal score = clamp01(scale(signal.getPositionManagementSignal()));
        boolean blocked = isBlocked(signal);
        boolean qualityDegraded = isTradeDisabled(asset) || isQuoteStale(asset);
        Map<String, Object> pressure = parseJsonMap(signal.getPressureReasonJson());
        boolean volumeSame = boolField(pressure, "volume_regime_same");
        boolean sellDetected = boolField(pressure, "sell_pressure_detected");
        boolean buyDetected = boolField(pressure, "buy_pressure_detected");
        boolean sellNegative = boolField(pressure, "sell_pressure_is_negative");
        boolean buyPositive = boolField(pressure, "buy_pressure_is_positive");
        boolean neutralized = volumeSame && (sellDetected || buyDetected) && !sellNegative && !buyPositive;

        String badge;
        String reason;
        String recommendationState;
        if (blocked) {
            badge = "차단";
            reason = "리스크 정책 차단";
            recommendationState = "BLOCKED";
        } else if (neutralized) {
            badge = "압력중립";
            reason = "연속 매수/매도 신호가 있으나 거래량 동일 구간으로 자동 단정 보류";
            recommendationState = "WATCH_ONLY";
        } else if (signal.getAction() == SignalActionType.BUY_CANDIDATE || signal.getAction() == SignalActionType.SELL_CANDIDATE) {
            badge = qualityDegraded ? "품질주의" : "대응";
            reason = "차트/압력/변동성 기반 대응 후보";
            recommendationState = qualityDegraded ? "WARN" : "RECOMMEND";
        } else {
            badge = qualityDegraded ? "품질주의" : "관찰";
            reason = "차트 대응 조건 대기";
            recommendationState = qualityDegraded ? "WARN" : "WATCH_ONLY";
        }

        return new PanelStrategyEvaluation(
                score,
                layerBonus(asset == null ? null : asset.getUniverseLayer()),
                strategyKey(),
                "차트 대응/압력 확인",
                "포지션관리",
                scale(signal.getPositionManagementSignal()),
                badge,
                reason,
                recommendationState,
                qualityDegraded,
                "position_signal>chart_confidence>pressure_reason");
    }

    @Override
    public String strategyKey() {
        return "CHART_RESPONSE";
    }

    @Override
    public String signalWindow() {
        return "1m";
    }

    @Override
    public List<SignalActionType> allowedActions() {
        return List.of(
                SignalActionType.BUY_CANDIDATE,
                SignalActionType.SELL_CANDIDATE,
                SignalActionType.HOLD,
                SignalActionType.WATCH,
                SignalActionType.BUY_LOCK);
    }

    @Override
    protected BigDecimal layerBonus(UniverseLayerType layer) {
        return BigDecimal.ZERO;
    }
}

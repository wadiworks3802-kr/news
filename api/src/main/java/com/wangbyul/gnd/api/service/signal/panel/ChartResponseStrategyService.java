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
        boolean tradeDisabled = isTradeDisabled(asset);
        boolean quoteStale = isQuoteStale(asset);
        boolean qualityDegraded = tradeDisabled || quoteStale;
        Map<String, Object> pressure = parseJsonMap(signal.getPressureReasonJson());
        boolean volumeSame = boolField(pressure, "volume_regime_same");
        boolean sellDetected = boolField(pressure, "sell_pressure_detected");
        boolean buyDetected = boolField(pressure, "buy_pressure_detected");
        boolean sellNegative = boolField(pressure, "sell_pressure_is_negative");
        boolean buyPositive = boolField(pressure, "buy_pressure_is_positive");
        boolean neutralized = volumeSame && (sellDetected || buyDetected) && !sellNegative && !buyPositive;
        BigDecimal chart = scale(signal.getChartConfidence());
        BigDecimal position = scale(signal.getPositionManagementSignal());
        String avgDownState = Boolean.TRUE.equals(signal.getAvgDownAllowed())
                ? ("허용(" + scale(signal.getAvgDownNextBuyRatio()) + ")")
                : "보류";

        String badge;
        String reason;
        String recommendationState;
        if (blocked) {
            badge = "차단";
            reason = "차단사유=" + safeString(signal.getBlockedReason(), "RISK_POLICY")
                    + " · 포지션 " + position
                    + " · 차트신뢰 " + chart;
            recommendationState = "BLOCKED";
        } else if (neutralized) {
            badge = "압력중립";
            reason = "매수/매도 연속 신호 감지, 거래량 동일구간으로 자동 단정 보류"
                    + " · 포지션 " + position
                    + " · 차트신뢰 " + chart
                    + " · 평단가 " + avgDownState
                    + qualityHint(tradeDisabled, quoteStale);
            recommendationState = "WATCH_ONLY";
        } else if (signal.getAction() == SignalActionType.BUY_CANDIDATE || signal.getAction() == SignalActionType.SELL_CANDIDATE) {
            badge = qualityDegraded ? "품질주의" : "대응";
            reason = "포지션 " + position + " · 차트신뢰 " + chart
                    + " · 매도압력 " + sellDetected + "/" + sellNegative
                    + " · 매수압력 " + buyDetected + "/" + buyPositive
                    + " · 평단가 " + avgDownState
                    + qualityHint(tradeDisabled, quoteStale);
            recommendationState = qualityDegraded ? "WARN" : "RECOMMEND";
        } else {
            badge = qualityDegraded ? "품질주의" : "관찰";
            reason = "포지션 " + position + " · 차트신뢰 " + chart
                    + " · 평단가 " + avgDownState
                    + " · 차트 대응 조건 대기"
                    + qualityHint(tradeDisabled, quoteStale);
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

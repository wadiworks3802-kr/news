package com.wangbyul.gnd.api.service.signal.panel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.AssetType;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.domain.UniverseLayerType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * 전략 패널 분리 서비스 규칙 테스트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
class SignalPanelStrategyServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void chartResponseShouldShowNeutralizedBadgeWhenVolumeSameSuppressesPressureClassification() {
        ChartResponseStrategyService service = new ChartResponseStrategyService(objectMapper);
        TradingSignalEntity signal = baseSignal();
        signal.setAction(SignalActionType.WATCH);
        signal.setPositionManagementSignal(new BigDecimal("0.62"));
        signal.setPressureReasonJson("""
                {
                  "volume_regime_same": true,
                  "sell_pressure_detected": true,
                  "buy_pressure_detected": false,
                  "sell_pressure_is_negative": false,
                  "buy_pressure_is_positive": false
                }
                """);

        PanelStrategyEvaluation eval = service.evaluate(signal, activeAsset());
        assertThat(eval.stateBadge()).isEqualTo("압력중립");
        assertThat(eval.recommendationState()).isEqualTo("WATCH_ONLY");
    }

    @Test
    void scalpShouldShowDataGapBadgeWhenProbabilityDataIsInsufficient() {
        ScalpStrategyService service = new ScalpStrategyService(objectMapper);
        TradingSignalEntity signal = baseSignal();
        signal.setAction(SignalActionType.WATCH);
        signal.setScalpSignalScore(new BigDecimal("0.12"));
        signal.setProbabilityReasonBreakdownJson("""
                {"data_state":"INSUFFICIENT_DATA"}
                """);

        PanelStrategyEvaluation eval = service.evaluate(signal, activeAsset());
        assertThat(eval.stateBadge()).isEqualTo("데이터부족");
        assertThat(eval.recommendationState()).isEqualTo("DATA_GAP");
    }

    private TradingSignalEntity baseSignal() {
        TradingSignalEntity entity = new TradingSignalEntity();
        entity.setId("sig-test");
        entity.setAssetCode("A001");
        entity.setCombinedConfidence(new BigDecimal("0.51"));
        entity.setGeneratedAt(OffsetDateTime.now());
        return entity;
    }

    private AssetUniverseEntity activeAsset() {
        AssetUniverseEntity asset = new AssetUniverseEntity();
        asset.setAssetCode("A001");
        asset.setAssetName("테스트자산");
        asset.setCountry("KR");
        asset.setAssetType(AssetType.STOCK);
        asset.setUniverseLayer(UniverseLayerType.CORE);
        asset.setIsTradeEnabled(true);
        asset.setLastQuoteReceivedAt(OffsetDateTime.now().minusMinutes(10));
        return asset;
    }
}


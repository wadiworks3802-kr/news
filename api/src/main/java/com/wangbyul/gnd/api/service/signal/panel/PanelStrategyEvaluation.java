package com.wangbyul.gnd.api.service.signal.panel;

import java.math.BigDecimal;

/**
 * 전략 패널 선택/표시용 평가 결과 모델.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record PanelStrategyEvaluation(
        BigDecimal panelSignalScore,
        BigDecimal layerBonus,
        String strategyKey,
        String panelPurpose,
        String primaryMetricLabel,
        BigDecimal primaryMetricValue,
        String stateBadge,
        String stateReason,
        String recommendationState,
        boolean qualityDegraded,
        String sortBasis) {
}


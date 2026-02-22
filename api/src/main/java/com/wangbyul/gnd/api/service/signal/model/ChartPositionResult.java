package com.wangbyul.gnd.api.service.signal.model;

import java.math.BigDecimal;

/**
 * 차트 대응/평단가 전략 결과 모델.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record ChartPositionResult(
        BigDecimal positionManagementSignal,
        BigDecimal chartConfidence,
        boolean longBias,
        boolean shortBias,
        boolean trendBreakdownSevere,
        boolean newHighBreakout,
        boolean newLowBreakdown,
        BigDecimal atrPct,
        BigDecimal volumeRatio,
        BigDecimal trendSlopePct,
        boolean avgDownAllowed,
        int avgDownStage,
        BigDecimal avgDownNextBuyRatio,
        String avgDownReason,
        String riskWarning,
        String chartRuleHitsJson) {
}

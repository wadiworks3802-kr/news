package com.wangbyul.gnd.api.service.signal.model;

import java.math.BigDecimal;

/**
 * 연속 매수/매도 압력 탐지 결과 모델.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record PressureDetectionResult(
        boolean sellPressureDetected,
        boolean sellPressureNegative,
        boolean buyPressureDetected,
        boolean buyPressurePositive,
        boolean volumeRegimeSame,
        int windowBars,
        BigDecimal sellPressureRatio,
        BigDecimal buyPressureRatio,
        BigDecimal volumeDiffPct,
        BigDecimal pressureThreshold,
        String pressureReasonJson) {
}

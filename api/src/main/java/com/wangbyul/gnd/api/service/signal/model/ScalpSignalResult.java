package com.wangbyul.gnd.api.service.signal.model;

import java.math.BigDecimal;

/**
 * 단타 뉴스 엔진 결과 모델.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record ScalpSignalResult(
        BigDecimal scalpSignalScore,
        BigDecimal goodNewsProbability,
        BigDecimal badNewsProbability,
        BigDecimal newsConfidence,
        int matchedNewsCount,
        String probabilityReasonBreakdownJson) {
}

package com.wangbyul.gnd.api.service.signal.model;

import java.math.BigDecimal;

/**
 * 1주 컨텍스트 분석 결과 모델.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record WeeklyContextResult(
        int newsCount7d,
        BigDecimal positiveNewsRatio,
        BigDecimal negativeNewsRatio,
        BigDecimal priceTrendScore,
        BigDecimal volumeTrendScore,
        BigDecimal volatilityScore,
        BigDecimal weeklyContextScore) {
}


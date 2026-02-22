package com.wangbyul.gnd.api.service.signal.model;

import java.math.BigDecimal;

/**
 * 6개월 발굴 엔진 결과 모델.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record DiscoveryResult(
        BigDecimal discoveryScore,
        int candidateRank,
        String candidateReasonJson) {
}


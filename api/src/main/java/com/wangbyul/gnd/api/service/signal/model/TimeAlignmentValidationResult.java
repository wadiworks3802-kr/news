package com.wangbyul.gnd.api.service.signal.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 뉴스-가격 시간정렬 검증 결과 모델.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record TimeAlignmentValidationResult(
        int invalidPublishFetchOrderCount,
        int delayedTranslationCount,
        boolean futureDataDetected,
        BigDecimal penaltyRate,
        List<String> warnings) {
}

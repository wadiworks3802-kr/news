package com.wangbyul.gnd.core.domain;

/**
 * 시장데이터 품질 갭 이벤트 타입.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public enum MarketGapEventType {
    MISSING_BAR,
    DELAYED_QUOTE,
    DUPLICATE_BAR,
    OUTLIER_PRICE,
    TIME_REVERSAL
}

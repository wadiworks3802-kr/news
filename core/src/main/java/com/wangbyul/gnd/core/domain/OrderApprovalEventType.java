package com.wangbyul.gnd.core.domain;

/**
 * 주문 승인 파이프라인 이벤트 유형.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public enum OrderApprovalEventType {
    ANALYZE_SNAPSHOT,
    RECOMMEND_CREATED,
    APPROVED,
    REJECTED,
    ORDER_REQUESTED,
    ORDER_EXECUTED,
    ORDER_FAILED
}

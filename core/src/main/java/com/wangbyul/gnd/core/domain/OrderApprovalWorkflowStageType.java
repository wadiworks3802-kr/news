package com.wangbyul.gnd.core.domain;

/**
 * 주문 승인 파이프라인 단계.
 *
 * 필수 단계(ANALYZE -> RECOMMEND -> APPROVE -> ORDER_REQUESTED -> ORDER_EXECUTED)를 포함하며,
 * 준비 단계 운영을 위해 REJECTED/ORDER_FAILED 보조 단계를 함께 제공한다.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public enum OrderApprovalWorkflowStageType {
    ANALYZE,
    RECOMMEND,
    APPROVE,
    REJECTED,
    ORDER_REQUESTED,
    ORDER_EXECUTED,
    ORDER_FAILED
}

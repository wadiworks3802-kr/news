package com.wangbyul.gnd.core.exception;
/**
 * HumanReviewReason 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

public enum HumanReviewReason {
    PARSER_FAILED,
    LANGUAGE_NOT_DETECTED,
    TRANSLATION_GATE_FAILED,
    SUMMARY_GATE_FAILED,
    DUPLICATE_SURGE,
    DISPUTED,
    ROBOTS_DISALLOWED,
    TTL_EXPIRED
}

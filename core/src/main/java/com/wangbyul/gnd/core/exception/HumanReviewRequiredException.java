package com.wangbyul.gnd.core.exception;
/**
 * HumanReviewRequiredException 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

public class HumanReviewRequiredException extends RuntimeException {

    private final HumanReviewReason reason;

    public HumanReviewRequiredException(HumanReviewReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public HumanReviewReason getReason() {
        return reason;
    }
}

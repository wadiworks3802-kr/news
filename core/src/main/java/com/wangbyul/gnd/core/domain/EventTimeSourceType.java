package com.wangbyul.gnd.core.domain;

/**
 * 뉴스 이벤트 시각 기준 필드 타입.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public enum EventTimeSourceType {
    PUB_UTC,
    FETCH_UTC,
    CREATED_AT,
    PUBLISHED_AT_UTC
}

package com.wangbyul.gnd.core.domain;

/**
 * 티커 별칭 검증 체크 타입.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public enum TickerAliasCheckType {
    FORMAT,
    EXCHANGE,
    NAME_MAPPING,
    DUPLICATE_ALIAS,
    LOCALE_CONFLICT
}

package com.wangbyul.gnd.core.market.dto;

import java.time.OffsetDateTime;

/**
 * Provider 헬스체크 표준 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record MarketProviderHealthDto(
        String providerName,
        MarketProviderHealthStatus status,
        String message,
        Long latencyMs,
        OffsetDateTime checkedAtUtc) {
}

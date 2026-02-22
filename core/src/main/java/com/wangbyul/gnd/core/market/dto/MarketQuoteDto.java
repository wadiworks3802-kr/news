package com.wangbyul.gnd.core.market.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Provider 응답을 내부 표준으로 정규화한 시세 스냅샷 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record MarketQuoteDto(
        MarketAssetMetaDto meta,
        OffsetDateTime snapshotUtc,
        OffsetDateTime quoteTimeUtc,
        BigDecimal lastPrice,
        BigDecimal changePct,
        BigDecimal bidPrice,
        BigDecimal askPrice,
        BigDecimal bidSize,
        BigDecimal askSize,
        BigDecimal volume,
        String providerName) {
}

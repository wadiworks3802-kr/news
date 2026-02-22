package com.wangbyul.gnd.core.market.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Provider 응답을 내부 표준으로 정규화한 OHLCV 바 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record MarketPriceBarDto(
        MarketAssetMetaDto meta,
        OffsetDateTime barTimeUtc,
        String timeframe,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal closePrice,
        BigDecimal volume,
        String providerName) {
}

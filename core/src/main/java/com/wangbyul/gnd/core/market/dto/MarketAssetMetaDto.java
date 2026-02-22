package com.wangbyul.gnd.core.market.dto;

/**
 * 시장 데이터 표준 메타 정보 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record MarketAssetMetaDto(
        String assetCode,
        String assetName,
        String country,
        String theme,
        String assetType) {
}

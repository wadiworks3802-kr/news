package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

/**
 * 1주 뉴스+차트 맥락 점수 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Builder
public class WeeklyContextDto {

    @JsonProperty("asset_code")
    private String assetCode;

    @JsonProperty("news_count_7d")
    private int newsCount7d;

    @JsonProperty("positive_news_ratio")
    private BigDecimal positiveNewsRatio;

    @JsonProperty("negative_news_ratio")
    private BigDecimal negativeNewsRatio;

    @JsonProperty("price_trend_score")
    private BigDecimal priceTrendScore;

    @JsonProperty("volume_trend_score")
    private BigDecimal volumeTrendScore;

    @JsonProperty("volatility_score")
    private BigDecimal volatilityScore;

    @JsonProperty("weekly_context_score")
    private BigDecimal weeklyContextScore;
}


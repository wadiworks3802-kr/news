package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 포트폴리오 리스크 패널 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Builder
public class PaperTradeRiskDto {

    @JsonProperty("capital_total")
    private BigDecimal capitalTotal;

    @JsonProperty("invested_amount")
    private BigDecimal investedAmount;

    @JsonProperty("cash_remaining")
    private BigDecimal cashRemaining;

    @JsonProperty("max_position_ratio_per_asset")
    private BigDecimal maxPositionRatioPerAsset;

    @JsonProperty("max_theme_exposure_ratio")
    private BigDecimal maxThemeExposureRatio;

    @JsonProperty("max_country_exposure_ratio")
    private BigDecimal maxCountryExposureRatio;

    @JsonProperty("max_open_positions")
    private int maxOpenPositions;

    @JsonProperty("take_profit_pct")
    private BigDecimal takeProfitPct;

    @JsonProperty("stop_loss_pct")
    private BigDecimal stopLossPct;

    @JsonProperty("position_exposure")
    private List<ExposureItemDto> positionExposure;

    @JsonProperty("theme_exposure")
    private List<ExposureItemDto> themeExposure;

    @JsonProperty("country_exposure")
    private List<ExposureItemDto> countryExposure;
}


package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 리스크 정책 설정 변경 요청 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
public class AdminRiskPolicyRequest {

    @JsonProperty("max_position_ratio_per_asset")
    @DecimalMin("0.01")
    @DecimalMax("1.0")
    private BigDecimal maxPositionRatioPerAsset;

    @JsonProperty("max_theme_exposure_ratio")
    @DecimalMin("0.01")
    @DecimalMax("1.0")
    private BigDecimal maxThemeExposureRatio;

    @JsonProperty("max_country_exposure_ratio")
    @DecimalMin("0.01")
    @DecimalMax("1.0")
    private BigDecimal maxCountryExposureRatio;

    @JsonProperty("max_open_positions")
    @Min(1)
    private Integer maxOpenPositions;

    @JsonProperty("take_profit_pct")
    @DecimalMin("0.1")
    private BigDecimal takeProfitPct;

    @JsonProperty("stop_loss_pct")
    @DecimalMin("0.1")
    private BigDecimal stopLossPct;

    @JsonProperty("reanalysis_lock_after_tp_sl")
    private Boolean reanalysisLockAfterTpSl;

    @JsonProperty("reanalysis_lock_minutes")
    @Min(1)
    private Integer reanalysisLockMinutes;
}


package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

/**
 * 연속 매수/매도 압력 분석 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Builder
public class PressureAnalysisDto {

    @JsonProperty("asset_code")
    private String assetCode;

    @JsonProperty("sell_pressure_detected")
    private boolean sellPressureDetected;

    @JsonProperty("sell_pressure_is_negative")
    private boolean sellPressureIsNegative;

    @JsonProperty("buy_pressure_detected")
    private boolean buyPressureDetected;

    @JsonProperty("buy_pressure_is_positive")
    private boolean buyPressureIsPositive;

    @JsonProperty("volume_regime_same")
    private boolean volumeRegimeSame;

    @JsonProperty("window_bars")
    private int windowBars;
}


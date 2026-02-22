package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wangbyul.gnd.core.domain.OrderSideType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 모의매매 주문 요청 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
public class PaperTradeOrderRequestDto {

    @NotBlank
    @JsonProperty("asset_code")
    private String assetCode;

    @NotNull
    @JsonProperty("order_side")
    private OrderSideType orderSide;

    @JsonProperty("signal_id")
    private String signalId;
}


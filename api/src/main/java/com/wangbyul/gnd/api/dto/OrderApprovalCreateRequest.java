package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wangbyul.gnd.core.domain.OrderSideType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 주문 승인 파이프라인 추천 생성 요청 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
public class OrderApprovalCreateRequest {

    @JsonProperty("signal_id")
    @NotBlank
    private String signalId;

    @JsonProperty("order_side")
    private OrderSideType orderSide;

    @JsonProperty("requested_by")
    @Pattern(regexp = "^[A-Za-z0-9_\\-.]{0,80}$")
    private String requestedBy = "admin-ui";

    @JsonProperty("request_reason")
    private String requestReason;

    @JsonProperty("allow_duplicate")
    private Boolean allowDuplicate = false;
}

package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 승인된 추천의 주문 요청(모의주문) 실행 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
public class OrderApprovalOrderRequest {

    @JsonProperty("requested_by")
    @Pattern(regexp = "^[A-Za-z0-9_\\-.]{0,80}$")
    private String requestedBy = "admin-ui";

    @JsonProperty("request_reason")
    private String requestReason;

    @JsonProperty("force_paper")
    private Boolean forcePaper = true;
}

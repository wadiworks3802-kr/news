package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 주문 승인 파이프라인 반려 요청 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
public class OrderApprovalRejectRequest {

    @JsonProperty("rejected_by")
    @Pattern(regexp = "^[A-Za-z0-9_\\-.]{0,80}$")
    private String rejectedBy = "admin-ui";

    @JsonProperty("reject_reason")
    @NotBlank
    private String rejectReason;
}

package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 관리자 기능 토글 부분 수정 요청 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
public class FeatureTogglePatchRequest {

    private Boolean enabled;
    private String reason;

    @JsonProperty("updated_by")
    @Pattern(regexp = "^[A-Za-z0-9_\\-.]{0,80}$")
    private String updatedBy = "admin";
}

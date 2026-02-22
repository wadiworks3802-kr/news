package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wangbyul.gnd.core.domain.FeatureScopeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 관리자 기능 토글 생성/갱신 요청 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
public class FeatureToggleUpsertRequest {

    @JsonProperty("feature_key")
    @NotBlank
    @Pattern(regexp = "^[A-Z0-9_\\-]{3,80}$")
    private String featureKey;

    @NotNull
    private Boolean enabled;

    @JsonProperty("scope_type")
    @NotNull
    private FeatureScopeType scopeType = FeatureScopeType.GLOBAL;

    @JsonProperty("scope_value")
    private String scopeValue;

    private String reason;

    @JsonProperty("updated_by")
    @Pattern(regexp = "^[A-Za-z0-9_\\-.]{0,80}$")
    private String updatedBy = "admin";
}

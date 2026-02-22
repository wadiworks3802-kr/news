package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wangbyul.gnd.core.domain.FeatureScopeType;
import java.time.OffsetDateTime;
import lombok.Builder;
import lombok.Getter;

/**
 * 관리자 기능 토글 응답 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Builder
public class FeatureToggleDto {

    private Long id;

    @JsonProperty("feature_key")
    private String featureKey;

    private Boolean enabled;

    @JsonProperty("scope_type")
    private FeatureScopeType scopeType;

    @JsonProperty("scope_value")
    private String scopeValue;

    private String reason;

    @JsonProperty("updated_by")
    private String updatedBy;

    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    @JsonProperty("trace_id")
    private String traceId;
}

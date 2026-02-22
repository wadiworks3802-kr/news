package com.wangbyul.gnd.api.dto;

import com.wangbyul.gnd.core.domain.SourceGrade;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
/**
 * AdminSourceRequest 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Getter
@Setter
public class AdminSourceRequest {

    @NotBlank
    private String sid;

    @NotBlank
    @Pattern(regexp = "^[A-Z]{2,5}$")
    private String country;

    @NotNull
    private SourceGrade sourceGrade;

    @NotNull
    @Min(1)
    private Integer priority;

    @NotNull
    private Boolean allowFetch;

    @NotNull
    private Boolean allowStoreRaw;

    @NotNull
    private Boolean allowStoreDerived;

    @NotNull
    @Min(1)
    @Max(604800)
    private Integer cacheTtlSeconds;

    @NotBlank
    private String licensePolicy;

    @NotBlank
    private String robotsPolicy;

    @Pattern(regexp = "^(https?://).+", message = "url must start with http:// or https://")
    private String endpointUrl;
}

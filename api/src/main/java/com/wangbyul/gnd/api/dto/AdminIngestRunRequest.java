package com.wangbyul.gnd.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
/**
 * AdminIngestRunRequest 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Getter
@Setter
public class AdminIngestRunRequest {

    @Pattern(regexp = "^[a-zA-Z0-9._:-]{1,64}$", message = "sid format is invalid")
    private String sid;

    @Pattern(regexp = "^(P0|P1|P2|P3)$", message = "grade must be one of P0,P1,P2,P3")
    private String grade;

    @Min(1)
    @Max(500)
    private Integer limit = 50;
}

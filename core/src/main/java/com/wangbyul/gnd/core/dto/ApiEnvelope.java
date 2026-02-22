package com.wangbyul.gnd.core.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
/**
 * ApiEnvelope 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Getter
@Builder
public class ApiEnvelope<T> {

    private T data;
    private Map<String, Object> meta;

    @JsonProperty("trace_id")
    private String traceId;
}

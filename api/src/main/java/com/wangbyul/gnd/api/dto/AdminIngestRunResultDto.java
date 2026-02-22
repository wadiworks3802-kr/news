package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
/**
 * AdminIngestRunResultDto 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Getter
@Builder
public class AdminIngestRunResultDto {

    private int requested;
    private int processed;
    private int succeeded;
    private int failed;

    @JsonProperty("news_created")
    private long newsCreated;
}

package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/**
 * 저장된 백테스트 리포트 조회 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Builder
public class BacktestReportDto {

    @JsonProperty("run_id")
    private Long runId;

    private String country;
    private String theme;

    @JsonProperty("run_type")
    private String runType;

    @JsonProperty("started_at")
    private OffsetDateTime startedAt;

    @JsonProperty("ended_at")
    private OffsetDateTime endedAt;

    @JsonProperty("processed_count")
    private Integer processedCount;

    @JsonProperty("created_signal_count")
    private Integer createdSignalCount;

    @JsonProperty("result_summary_json")
    private Map<String, Object> resultSummaryJson;
}

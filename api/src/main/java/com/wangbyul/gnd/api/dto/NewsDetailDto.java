package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
/**
 * NewsDetailDto 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Getter
@Builder
public class NewsDetailDto {

    private String id;
    private String country;
    private String lang;
    private List<String> category;
    private String url;

    @JsonProperty("title_raw")
    private String titleRaw;

    @JsonProperty("body_raw")
    private String bodyRaw;

    @JsonProperty("title_ko")
    private String titleKo;

    @JsonProperty("summary_ko")
    private String summaryKo;

    @JsonProperty("pub_utc")
    private OffsetDateTime pubUtc;

    @JsonProperty("trust_score")
    private BigDecimal trustScore;

    @JsonProperty("evidence_spans")
    private List<String> evidenceSpans;
}

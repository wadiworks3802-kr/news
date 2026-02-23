package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
/**
 * 뉴스 목록 카드 응답 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 화면 카드에 필요한 최소 필드(제목/요약/출처/썸네일/링크)만 포함한다.
 */
public class NewsListItemDto {

    private String id;
    private String country;
    private String lang;
    private String source;
    private String url;
    private List<String> category;

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

    @JsonProperty("thumbnail_url")
    private String thumbnailUrl;

    @JsonProperty("thumbnail_source")
    private String thumbnailSource;

    @JsonProperty("thumbnail_status")
    private String thumbnailStatus;

    @JsonProperty("source_icon_url")
    private String sourceIconUrl;

    @JsonProperty("translation_pending")
    private Boolean translationPending;
}

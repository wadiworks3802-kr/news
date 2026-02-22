package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

/**
 * 주식 시그널 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 개인 참고용 확률 지표를 전달한다.
 */
@Getter
@Builder
public class StockSignalDto {

    @JsonProperty("stock_code")
    private String stockCode;

    @JsonProperty("stock_name")
    private String stockName;

    @JsonProperty("up_probability")
    private int upProbability;

    @JsonProperty("down_probability")
    private int downProbability;

    @JsonProperty("confidence")
    private int confidence;

    @JsonProperty("matched_articles")
    private int matchedArticles;

    @JsonProperty("reason")
    private String reason;
}

package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/**
 * 백테스트 비교 리포트 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Builder
public class BacktestComparisonDto {

    @JsonProperty("report_run_id")
    private Long reportRunId;

    @JsonProperty("generated_at")
    private OffsetDateTime generatedAt;

    private String country;
    private String theme;
    private String period;

    @JsonProperty("baseline_news_only_return")
    private BigDecimal baselineNewsOnlyReturn;

    @JsonProperty("baseline_chart_only_return")
    private BigDecimal baselineChartOnlyReturn;

    @JsonProperty("fusion_return")
    private BigDecimal fusionReturn;

    @JsonProperty("before_policy_mdd")
    private BigDecimal beforePolicyMdd;

    @JsonProperty("after_policy_mdd")
    private BigDecimal afterPolicyMdd;

    @JsonProperty("overtrade_before")
    private Integer overtradeBefore;

    @JsonProperty("overtrade_after")
    private Integer overtradeAfter;

    @JsonProperty("buy_lock_effect")
    private BigDecimal buyLockEffect;

    @JsonProperty("avg_down_policy_effect")
    private BigDecimal avgDownPolicyEffect;

    @JsonProperty("summary")
    private String summary;

    @JsonProperty("validation_mode")
    private String validationMode;

    @JsonProperty("out_of_sample_required")
    private Boolean outOfSampleRequired;

    @JsonProperty("look_ahead_violation_count")
    private Integer lookAheadViolationCount;

    @JsonProperty("minimum_trade_count_warning")
    private Boolean minimumTradeCountWarning;

    @JsonProperty("in_sample_trade_count")
    private Integer inSampleTradeCount;

    @JsonProperty("out_of_sample_trade_count")
    private Integer outOfSampleTradeCount;

    @JsonProperty("policy_before_return")
    private BigDecimal policyBeforeReturn;

    @JsonProperty("policy_after_return")
    private BigDecimal policyAfterReturn;

    @JsonProperty("report_json")
    private Map<String, Object> reportJson;

    @JsonProperty("warnings")
    private List<String> warnings;
}

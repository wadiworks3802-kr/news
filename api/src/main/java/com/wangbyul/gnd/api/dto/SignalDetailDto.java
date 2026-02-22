package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wangbyul.gnd.core.domain.SignalActionType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 시그널 상세 팝업용 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Builder
public class SignalDetailDto {

    @JsonProperty("signal_id")
    private String signalId;

    @JsonProperty("asset_code")
    private String assetCode;

    @JsonProperty("asset_name")
    private String assetName;

    private SignalActionType action;

    @JsonProperty("good_news_probability")
    private BigDecimal goodNewsProbability;

    @JsonProperty("bad_news_probability")
    private BigDecimal badNewsProbability;

    @JsonProperty("weekly_context_score")
    private BigDecimal weeklyContextScore;

    @JsonProperty("combined_confidence")
    private BigDecimal combinedConfidence;

    @JsonProperty("probability_reason_breakdown_json")
    private String probabilityReasonBreakdownJson;

    @JsonProperty("pressure_reason_json")
    private String pressureReasonJson;

    @JsonProperty("news_evidence")
    private List<String> newsEvidence;

    @JsonProperty("chart_evidence")
    private List<String> chartEvidence;

    @JsonProperty("pressure_analysis")
    private PressureAnalysisDto pressureAnalysis;

    @JsonProperty("risk_checks")
    private List<String> riskChecks;

    @JsonProperty("blocked_reason")
    private String blockedReason;

    @JsonProperty("reason_json")
    private String reasonJson;

    @JsonProperty("generated_at")
    private OffsetDateTime generatedAt;
}

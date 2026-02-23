package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wangbyul.gnd.core.domain.SignalActionType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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

    @JsonProperty("news_confidence")
    private BigDecimal newsConfidence;

    @JsonProperty("weekly_context_score")
    private BigDecimal weeklyContextScore;

    @JsonProperty("combined_confidence")
    private BigDecimal combinedConfidence;

    @JsonProperty("analysis_state")
    private String analysisState;

    @JsonProperty("data_state")
    private String dataState;

    @JsonProperty("probability_reason_breakdown_json")
    private String probabilityReasonBreakdownJson;

    @JsonProperty("pressure_reason_json")
    private String pressureReasonJson;

    @JsonProperty("top_positive_factors_json")
    private String topPositiveFactorsJson;

    @JsonProperty("top_negative_factors_json")
    private String topNegativeFactorsJson;

    @JsonProperty("explain_text")
    private String explainText;

    @JsonProperty("news_alignment_result_json")
    private String newsAlignmentResultJson;

    @JsonProperty("data_freshness_json")
    private String dataFreshnessJson;

    @JsonProperty("dedup_result_json")
    private String dedupResultJson;

    @JsonProperty("rag_context_refs_json")
    private String ragContextRefsJson;

    @JsonProperty("assistant_rag")
    private AssistantRagInsightDto assistantRag;

    @JsonProperty("strategy_evidence")
    private Map<String, Object> strategyEvidence;

    @JsonProperty("strategy_comparison")
    private List<Map<String, Object>> strategyComparison;

    @JsonProperty("risk_guidance")
    private Map<String, Object> riskGuidance;

    @JsonProperty("news_evidence")
    private List<String> newsEvidence;

    @JsonProperty("chart_evidence")
    private List<String> chartEvidence;

    @JsonProperty("price_evidence")
    private List<String> priceEvidence;

    @JsonProperty("volume_evidence")
    private List<String> volumeEvidence;

    @JsonProperty("risk_evidence")
    private List<String> riskEvidence;

    @JsonProperty("decision_why")
    private String decisionWhy;

    @JsonProperty("missing_requirements")
    private List<String> missingRequirements;

    @JsonProperty("change_conditions")
    private List<String> changeConditions;

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

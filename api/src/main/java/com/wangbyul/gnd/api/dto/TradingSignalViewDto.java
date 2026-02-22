package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wangbyul.gnd.core.domain.MarketRegimeType;
import com.wangbyul.gnd.core.domain.SignalActionType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 시그널 조회용 공통 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Builder
public class TradingSignalViewDto {

    @JsonProperty("signal_id")
    private String signalId;

    @JsonProperty("asset_code")
    private String assetCode;

    @JsonProperty("asset_name")
    private String assetName;

    private String country;
    private String theme;
    private SignalActionType action;

    @JsonProperty("market_regime")
    private MarketRegimeType marketRegime;

    @JsonProperty("good_news_probability")
    private BigDecimal goodNewsProbability;

    @JsonProperty("bad_news_probability")
    private BigDecimal badNewsProbability;

    @JsonProperty("news_confidence")
    private BigDecimal newsConfidence;

    @JsonProperty("probability_reason_breakdown_json")
    private String probabilityReasonBreakdownJson;

    @JsonProperty("chart_confidence")
    private BigDecimal chartConfidence;

    @JsonProperty("combined_confidence")
    private BigDecimal combinedConfidence;

    @JsonProperty("weekly_context_score")
    private BigDecimal weeklyContextScore;

    @JsonProperty("scalp_signal_score")
    private BigDecimal scalpSignalScore;

    @JsonProperty("swing_signal_score")
    private BigDecimal swingSignalScore;

    @JsonProperty("position_management_signal")
    private BigDecimal positionManagementSignal;

    @JsonProperty("discovery_score")
    private BigDecimal discoveryScore;

    @JsonProperty("pressure_reason_json")
    private String pressureReasonJson;

    @JsonProperty("risk_checks")
    private List<String> riskChecks;

    @JsonProperty("blocked_reason")
    private String blockedReason;

    @JsonProperty("reason_json")
    private String reasonJson;

    @JsonProperty("generated_at")
    private OffsetDateTime generatedAt;
}

package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/**
 * 포트폴리오 리스크 패널 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Builder
public class PaperTradeRiskDto {

    @JsonProperty("capital_total")
    private BigDecimal capitalTotal;

    @JsonProperty("invested_amount")
    private BigDecimal investedAmount;

    @JsonProperty("cash_remaining")
    private BigDecimal cashRemaining;

    @JsonProperty("max_position_ratio_per_asset")
    private BigDecimal maxPositionRatioPerAsset;

    @JsonProperty("max_theme_exposure_ratio")
    private BigDecimal maxThemeExposureRatio;

    @JsonProperty("max_country_exposure_ratio")
    private BigDecimal maxCountryExposureRatio;

    @JsonProperty("max_open_positions")
    private int maxOpenPositions;

    @JsonProperty("take_profit_pct")
    private BigDecimal takeProfitPct;

    @JsonProperty("stop_loss_pct")
    private BigDecimal stopLossPct;

    @JsonProperty("position_exposure")
    private List<ExposureItemDto> positionExposure;

    @JsonProperty("theme_exposure")
    private List<ExposureItemDto> themeExposure;

    @JsonProperty("country_exposure")
    private List<ExposureItemDto> countryExposure;

    @JsonProperty("strategy_action_distribution")
    private Map<String, Map<String, Long>> strategyActionDistribution;

    @JsonProperty("strategy_blocked_count")
    private Map<String, Long> strategyBlockedCount;

    @JsonProperty("blocked_reason_distribution")
    private Map<String, Long> blockedReasonDistribution;

    @JsonProperty("duplicate_exposure_stats")
    private Map<String, Long> duplicateExposureStats;

    @JsonProperty("buy_lock_count")
    private Integer buyLockCount;

    @JsonProperty("reanalysis_pending_count")
    private Integer reanalysisPendingCount;

    @JsonProperty("data_quality_degraded_assets")
    private List<String> dataQualityDegradedAssets;

    @JsonProperty("position_limit_warning_assets")
    private List<String> positionLimitWarningAssets;

    @JsonProperty("portfolio_diversification_warning")
    private Boolean portfolioDiversificationWarning;

    @JsonProperty("portfolio_diversification_warnings")
    private List<String> portfolioDiversificationWarnings;

    @JsonProperty("reference_only")
    private Boolean referenceOnly;

    @JsonProperty("reference_capital_basis")
    private BigDecimal referenceCapitalBasis;

    @JsonProperty("recommended_entry_ratio_pct")
    private BigDecimal recommendedEntryRatioPct;

    @JsonProperty("recommended_entry_amount")
    private BigDecimal recommendedEntryAmount;

    @JsonProperty("recommended_buy_split_ratios")
    private List<Integer> recommendedBuySplitRatios;

    @JsonProperty("recommended_sell_split_ratios")
    private List<Integer> recommendedSellSplitRatios;

    @JsonProperty("reanalysis_lock_minutes")
    private Integer reanalysisLockMinutes;
}

package com.wangbyul.gnd.api.dto;

import com.wangbyul.gnd.core.domain.MarketProviderJobType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 관리자 수동 시장데이터 수집 요청 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public class AdminMarketDataCollectRequest {

    @NotNull
    private MarketProviderJobType jobType;

    @Pattern(regexp = "^[A-Za-z0-9_-]{0,32}$", message = "provider must be alphanumeric")
    private String provider;

    @Pattern(regexp = "^[A-Za-z0-9]{0,8}$", message = "timeframe must be simple token")
    private String timeframe;

    @Min(value = 1, message = "barsPerAsset must be >= 1")
    private Integer barsPerAsset;

    @Pattern(regexp = "^[A-Za-z0-9_.:-]{0,64}$", message = "triggeredBy must be simple token")
    private String triggeredBy;

    public MarketProviderJobType getJobType() {
        return jobType;
    }

    public void setJobType(MarketProviderJobType jobType) {
        this.jobType = jobType;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public Integer getBarsPerAsset() {
        return barsPerAsset;
    }

    public void setBarsPerAsset(Integer barsPerAsset) {
        this.barsPerAsset = barsPerAsset;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
    }
}

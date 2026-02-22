package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import lombok.Builder;
import lombok.Getter;

/**
 * BUY_LOCK 상태 조회 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Builder
public class BuyLockStatusDto {

    @JsonProperty("asset_code")
    private String assetCode;

    @JsonProperty("asset_name")
    private String assetName;

    @JsonProperty("lock_reason")
    private String lockReason;

    @JsonProperty("lock_until")
    private OffsetDateTime lockUntil;

    @JsonProperty("last_reanalysis_at")
    private OffsetDateTime lastReanalysisAt;
}


package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wangbyul.gnd.core.domain.OrderStatusType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 모의매매 주문 처리 결과 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Builder
public class PaperTradeOrderResultDto {

    @JsonProperty("order_id")
    private Long orderId;

    @JsonProperty("asset_code")
    private String assetCode;

    @JsonProperty("status")
    private OrderStatusType status;

    @JsonProperty("request_ratio")
    private BigDecimal requestRatio;

    @JsonProperty("request_amount")
    private BigDecimal requestAmount;

    @JsonProperty("executed_price")
    private BigDecimal executedPrice;

    @JsonProperty("executed_amount")
    private BigDecimal executedAmount;

    @JsonProperty("risk_checks")
    private List<String> riskChecks;

    @JsonProperty("blocked_reason")
    private String blockedReason;

    @JsonProperty("created_at")
    private OffsetDateTime createdAt;
}


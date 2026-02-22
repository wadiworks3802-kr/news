package com.wangbyul.gnd.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 투자금/분할매수/분할매도 설정 변경 요청 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
public class AdminCapitalConfigRequest {

    @JsonProperty("capital_total")
    @DecimalMin("10000")
    private BigDecimal capitalTotal;

    @JsonProperty("buy_split_rules")
    @NotEmpty
    private List<Integer> buySplitRules;

    @JsonProperty("sell_split_rules")
    @NotEmpty
    private List<Integer> sellSplitRules;
}


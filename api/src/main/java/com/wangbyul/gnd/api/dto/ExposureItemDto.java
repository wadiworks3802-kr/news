package com.wangbyul.gnd.api.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

/**
 * 비중 노출 항목 DTO.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Builder
public class ExposureItemDto {

    private String key;
    private BigDecimal ratio;
    private BigDecimal amount;
}


package com.wangbyul.gnd.api.config;

import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 자금관리/리스크 정책 외부화 설정.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.risk")
public class RiskPolicyProperties {

    /**
     * 총 투자금.
     */
    private BigDecimal capitalTotal = BigDecimal.valueOf(1_000_000L);

    /**
     * 종목당 최대 비중(0~1).
     */
    private BigDecimal maxPositionRatioPerAsset = BigDecimal.valueOf(0.30d);

    /**
     * 테마당 최대 비중(0~1).
     */
    private BigDecimal maxThemeExposureRatio = BigDecimal.valueOf(0.45d);

    /**
     * 국가당 최대 비중(0~1).
     */
    private BigDecimal maxCountryExposureRatio = BigDecimal.valueOf(0.60d);

    /**
     * 동시 보유 가능한 최대 종목 수.
     */
    private Integer maxOpenPositions = 8;

    /**
     * 분할매수 비중(합계 100 기준).
     */
    private List<Integer> buySplitRules = List.of(30, 30, 40);

    /**
     * 분할매도 비중(합계 100 기준).
     */
    private List<Integer> sellSplitRules = List.of(30, 30, 40);

    /**
     * 익절 퍼센트.
     */
    private BigDecimal takeProfitPct = BigDecimal.valueOf(8.0d);

    /**
     * 손절 퍼센트(양수값).
     */
    private BigDecimal stopLossPct = BigDecimal.valueOf(5.0d);

    /**
     * 익절/손절 후 재분석 전까지 매수 잠금 여부.
     */
    private Boolean reanalysisLockAfterTpSl = true;

    /**
     * 재분석 잠금 시간(분).
     */
    private Integer reanalysisLockMinutes = 120;
}


package com.wangbyul.gnd.api.config;

import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시그널 정책 외부화 설정.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.signal")
public class SignalPolicyProperties {

    /**
     * 뉴스 분석 윈도우(분 단위).
     * 예: 10m, 30m, 1h, 1d, 1w -> [10,30,60,1440,10080]
     */
    private List<Integer> newsWindowsMinutes = List.of(10, 30, 60, 1440, 10080);

    /**
     * 이동평균 기간.
     */
    private List<Integer> maPeriods = List.of(5, 20, 60, 120);

    /**
     * 거래량 동일 판정 허용 오차(%).
     */
    private BigDecimal volumeSameTolerancePct = BigDecimal.valueOf(5.0d);

    /**
     * 연속 매수/매도 압력 탐지 윈도우(bar).
     */
    private Integer pressureWindowBars = 20;

    /**
     * 거래량 비교 윈도우(bar).
     */
    private Integer volumeWindowBars = 20;

    /**
     * 호재 확률 임계치(0~1).
     */
    private BigDecimal goodNewsThreshold = BigDecimal.valueOf(0.6d);

    /**
     * 악재 확률 임계치(0~1).
     */
    private BigDecimal badNewsThreshold = BigDecimal.valueOf(0.6d);

    /**
     * 시그널 재생성 쿨다운(분).
     */
    private Integer signalCooldownMinutes = 15;

    /**
     * RULE_V1 확률 계산 최소 표본 뉴스 수.
     */
    private Integer minNewsCountForProbability = 8;

    /**
     * RULE_V1에서 뉴스 사용 최소 신뢰도(0~1).
     */
    private BigDecimal newsTrustScoreMin = BigDecimal.valueOf(0.40d);

    /**
     * RULE_V1에서 긍정 감성 판정 최소 비율(0~1).
     */
    private BigDecimal positiveSentimentThreshold = BigDecimal.valueOf(0.55d);

    /**
     * RULE_V1에서 부정 감성 판정 최소 비율(0~1).
     */
    private BigDecimal negativeSentimentThreshold = BigDecimal.valueOf(0.55d);

    /**
     * RULE_V1 뉴스 시간감쇠 반감기(분).
     */
    private Integer newsDecayHalfLifeMinutes = 240;

    /**
     * 확률 계산 모드. 현재 RULE_V1만 허용.
     */
    private String probabilityCalculationMode = "RULE_V1";

    /**
     * 표본 부족 시 confidence 하한값(0~1).
     */
    private BigDecimal insufficientSampleConfidenceFloor = BigDecimal.valueOf(0.20d);

    /**
     * 뉴스-가격 시간정렬 검증 윈도우(분).
     */
    private Integer newsPriceAlignmentWindowMinutes = 60;

    /**
     * 번역 지연 감점 임계치(초).
     */
    private Integer translationDelayPenaltyThresholdSeconds = 900;

    /**
     * 시그널 계산 시 미래 시각 시장데이터 사용 차단 여부.
     */
    private Boolean blockFutureData = true;

    /**
     * 차트 룰셋 V1 ATR 기간(bar).
     */
    private Integer chartAtrPeriod = 14;

    /**
     * 차트 룰셋 V1 거래량 평균 비교 기간(bar).
     */
    private Integer chartVolumeAverageBars = 20;

    /**
     * 차트 룰셋 V1 고저점 돌파 확인 기간(bar).
     */
    private Integer chartHighLowLookbackBars = 20;

    /**
     * 차트 룰셋 V1 추세 기울기 산출 기간(bar).
     */
    private Integer chartTrendSlopeLookbackBars = 20;

    /**
     * 차트 룰셋 V1 LONG/SHORT 판정용 최소 기울기(%).
     */
    private BigDecimal chartTrendSlopeMinPct = BigDecimal.valueOf(0.15d);

    /**
     * 차트 룰셋 V1 거래량 증가 판정 최소 배수.
     */
    private BigDecimal chartVolumeRatioMin = BigDecimal.valueOf(1.05d);

    /**
     * 차트 룰셋 V1 LONG_BIAS 최소 룰 히트 수.
     */
    private Integer chartLongBiasMinRuleHits = 4;

    /**
     * 차트 룰셋 V1 SHORT_BIAS 최소 룰 히트 수.
     */
    private Integer chartShortBiasMinRuleHits = 4;

    /**
     * trend_breakdown_severe 기준: 종가가 MA120 대비 이탈한 비율(%).
     */
    private BigDecimal chartTrendBreakdownPct = BigDecimal.valueOf(8.0d);

    /**
     * trend_breakdown_severe 보조 조건 ATR 배수.
     */
    private BigDecimal chartTrendBreakdownAtrMultiplier = BigDecimal.valueOf(1.20d);

    /**
     * 평단가 허용 시 차트 confidence 하한(0~1).
     */
    private BigDecimal chartAvgDownMinConfidence = BigDecimal.valueOf(0.45d);

    /**
     * 퓨전 엔진에서 차트 우호 판정 임계치(0~1).
     */
    private BigDecimal fusionChartGoodThreshold = BigDecimal.valueOf(0.55d);

    /**
     * 퓨전 엔진에서 차트 약세 판정 임계치(0~1).
     */
    private BigDecimal fusionChartWeakThreshold = BigDecimal.valueOf(0.45d);

    /**
     * 퓨전 엔진에서 강신뢰 판정 임계치(0~1).
     */
    private BigDecimal fusionHighConfidenceThreshold = BigDecimal.valueOf(0.62d);

    /**
     * 연속 매수/매도 압력 탐지 최소 비율(0~1).
     */
    private BigDecimal pressureDetectionThreshold = BigDecimal.valueOf(0.70d);
}

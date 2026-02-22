package com.wangbyul.gnd.api.config;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 백테스트 검증 정책 설정.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.backtest")
public class BacktestPolicyProperties {

    /**
     * 백테스트 검증 모드.
     */
    private BacktestValidationMode validationMode = BacktestValidationMode.TRAIN_TEST_SPLIT;

    /**
     * OOS(out-of-sample) 데이터가 반드시 존재해야 하는지 여부.
     */
    private Boolean outOfSampleRequired = true;

    /**
     * 최소 거래 횟수 기준.
     */
    private Integer minimumTradeCountThreshold = 20;

    /**
     * 과최적화 경고 활성화 여부.
     */
    private Boolean overfitWarningEnabled = true;

    /**
     * TRAIN_TEST_SPLIT 훈련 비율(0~1).
     */
    private BigDecimal trainSplitRatio = BigDecimal.valueOf(0.70d);

    /**
     * WALK_FORWARD 훈련 구간 크기(건수 기준).
     */
    private Integer walkForwardTrainSize = 120;

    /**
     * WALK_FORWARD 테스트 구간 크기(건수 기준).
     */
    private Integer walkForwardTestSize = 30;

    /**
     * WALK_FORWARD 스텝 크기.
     */
    private Integer walkForwardStepSize = 30;

    /**
     * 백테스트에서 불러올 최대 시그널 수.
     */
    private Integer maxRows = 1000;
}

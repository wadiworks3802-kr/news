package com.wangbyul.gnd.api.config;

/**
 * 백테스트 검증 모드.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public enum BacktestValidationMode {
    NONE,
    TRAIN_TEST_SPLIT,
    WALK_FORWARD
}

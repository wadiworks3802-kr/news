package com.wangbyul.gnd.api.service.signal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 시그널 계산 공통 유틸.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public final class SignalMath {

    private SignalMath() {
    }

    public static BigDecimal clamp01(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal clampScore(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal min = BigDecimal.valueOf(-1);
        BigDecimal max = BigDecimal.ONE;
        if (value.compareTo(min) < 0) {
            return min;
        }
        if (value.compareTo(max) > 0) {
            return max;
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }
}


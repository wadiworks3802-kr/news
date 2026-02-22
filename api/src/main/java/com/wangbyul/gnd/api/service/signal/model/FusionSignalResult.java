package com.wangbyul.gnd.api.service.signal.model;

import com.wangbyul.gnd.core.domain.SignalActionType;
import java.math.BigDecimal;

/**
 * 최종 시그널 퓨전 결과 모델.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record FusionSignalResult(
        SignalActionType action,
        BigDecimal combinedConfidence,
        BigDecimal weeklyContextScore) {
}


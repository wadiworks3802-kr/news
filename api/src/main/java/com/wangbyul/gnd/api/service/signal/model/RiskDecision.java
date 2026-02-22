package com.wangbyul.gnd.api.service.signal.model;

import com.wangbyul.gnd.core.domain.SignalActionType;
import java.util.List;

/**
 * 리스크 정책 판정 결과 모델.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record RiskDecision(
        boolean allowed,
        SignalActionType finalAction,
        List<String> riskChecks,
        String blockedReason) {
}


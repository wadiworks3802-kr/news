package com.wangbyul.gnd.api.service.signal.model;

import com.wangbyul.gnd.core.domain.MarketRegimeType;
import java.math.BigDecimal;

/**
 * 시장 동향 엔진 결과 모델.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
public record MarketTrendSignalResult(
        MarketRegimeType marketRegime,
        BigDecimal themeStrengthScore,
        BigDecimal swingSignalScore) {
}


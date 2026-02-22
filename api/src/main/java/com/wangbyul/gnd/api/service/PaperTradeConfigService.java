package com.wangbyul.gnd.api.service;

import com.wangbyul.gnd.api.dto.AdminCapitalConfigRequest;
import com.wangbyul.gnd.api.dto.AdminRiskPolicyRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모의매매 정책 설정 변경 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class PaperTradeConfigService {

    private final StrategyConfigService strategyConfigService;

    public PaperTradeConfigService(StrategyConfigService strategyConfigService) {
        this.strategyConfigService = strategyConfigService;
    }

    @Transactional
    public Map<String, Object> applyRiskPolicy(AdminRiskPolicyRequest request) {
        if (request.getMaxPositionRatioPerAsset() != null) {
            strategyConfigService.putDecimal("app.risk.max-position-ratio-per-asset", request.getMaxPositionRatioPerAsset());
        }
        if (request.getMaxThemeExposureRatio() != null) {
            strategyConfigService.putDecimal("app.risk.max-theme-exposure-ratio", request.getMaxThemeExposureRatio());
        }
        if (request.getMaxCountryExposureRatio() != null) {
            strategyConfigService.putDecimal("app.risk.max-country-exposure-ratio", request.getMaxCountryExposureRatio());
        }
        if (request.getMaxOpenPositions() != null) {
            strategyConfigService.putInteger("app.risk.max-open-positions", request.getMaxOpenPositions());
        }
        if (request.getTakeProfitPct() != null) {
            strategyConfigService.putDecimal("app.risk.take-profit-pct", request.getTakeProfitPct());
        }
        if (request.getStopLossPct() != null) {
            strategyConfigService.putDecimal("app.risk.stop-loss-pct", request.getStopLossPct());
        }
        if (request.getReanalysisLockAfterTpSl() != null) {
            strategyConfigService.putBoolean("app.risk.reanalysis-lock-after-tp-sl", request.getReanalysisLockAfterTpSl());
        }
        if (request.getReanalysisLockMinutes() != null) {
            strategyConfigService.putInteger("app.risk.reanalysis-lock-minutes", request.getReanalysisLockMinutes());
        }

        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("updated", true);
        changed.put("policy", "risk");
        return changed;
    }

    @Transactional
    public Map<String, Object> applyCapitalConfig(AdminCapitalConfigRequest request) {
        validateSplitRules(request.getBuySplitRules(), "buy_split_rules");
        validateSplitRules(request.getSellSplitRules(), "sell_split_rules");

        if (request.getCapitalTotal() != null) {
            strategyConfigService.putDecimal("app.risk.capital-total", request.getCapitalTotal());
        }
        strategyConfigService.putIntegerList("app.risk.buy-split-rules", request.getBuySplitRules());
        strategyConfigService.putIntegerList("app.risk.sell-split-rules", request.getSellSplitRules());

        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("updated", true);
        changed.put("policy", "capital");
        return changed;
    }

    private void validateSplitRules(List<Integer> rules, String fieldName) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        int sum = rules.stream().mapToInt(v -> v == null ? 0 : v).sum();
        if (sum != 100) {
            throw new IllegalArgumentException(fieldName + " sum must be 100");
        }
        if (rules.stream().anyMatch(v -> v == null || v <= 0)) {
            throw new IllegalArgumentException(fieldName + " values must be positive");
        }
    }
}


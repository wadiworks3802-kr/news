package com.wangbyul.gnd.api.service;

import com.wangbyul.gnd.api.dto.AdminCapitalConfigRequest;
import com.wangbyul.gnd.api.dto.AdminRiskPolicyRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * PaperTradeConfigService 검증 테스트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
class PaperTradeConfigServiceTest {

    private StrategyConfigService strategyConfigService;
    private PaperTradeConfigService paperTradeConfigService;

    @BeforeEach
    void setUp() {
        strategyConfigService = Mockito.mock(StrategyConfigService.class);
        paperTradeConfigService = new PaperTradeConfigService(strategyConfigService);
    }

    @Test
    void applyRiskPolicyUpdatesDecimalAndIntegerValues() {
        AdminRiskPolicyRequest request = new AdminRiskPolicyRequest();
        request.setMaxPositionRatioPerAsset(BigDecimal.valueOf(0.25d));
        request.setMaxOpenPositions(7);
        request.setTakeProfitPct(BigDecimal.valueOf(9.0d));
        request.setStopLossPct(BigDecimal.valueOf(4.0d));

        paperTradeConfigService.applyRiskPolicy(request);

        Mockito.verify(strategyConfigService).putDecimal("app.risk.max-position-ratio-per-asset", BigDecimal.valueOf(0.25d));
        Mockito.verify(strategyConfigService).putInteger("app.risk.max-open-positions", 7);
        Mockito.verify(strategyConfigService).putDecimal("app.risk.take-profit-pct", BigDecimal.valueOf(9.0d));
        Mockito.verify(strategyConfigService).putDecimal("app.risk.stop-loss-pct", BigDecimal.valueOf(4.0d));
    }

    @Test
    void applyRiskPolicyUpdatesBooleanAndLockMinutes() {
        AdminRiskPolicyRequest request = new AdminRiskPolicyRequest();
        request.setReanalysisLockAfterTpSl(Boolean.TRUE);
        request.setReanalysisLockMinutes(180);

        paperTradeConfigService.applyRiskPolicy(request);

        Mockito.verify(strategyConfigService).putBoolean("app.risk.reanalysis-lock-after-tp-sl", Boolean.TRUE);
        Mockito.verify(strategyConfigService).putInteger("app.risk.reanalysis-lock-minutes", 180);
    }

    @Test
    void applyCapitalConfigWithValidSplitRules() {
        AdminCapitalConfigRequest request = new AdminCapitalConfigRequest();
        request.setCapitalTotal(BigDecimal.valueOf(2_000_000L));
        request.setBuySplitRules(List.of(20, 30, 50));
        request.setSellSplitRules(List.of(40, 30, 30));

        paperTradeConfigService.applyCapitalConfig(request);

        Mockito.verify(strategyConfigService).putDecimal("app.risk.capital-total", BigDecimal.valueOf(2_000_000L));
        Mockito.verify(strategyConfigService).putIntegerList("app.risk.buy-split-rules", List.of(20, 30, 50));
        Mockito.verify(strategyConfigService).putIntegerList("app.risk.sell-split-rules", List.of(40, 30, 30));
    }

    @Test
    void applyCapitalConfigRejectsInvalidBuySplitSum() {
        AdminCapitalConfigRequest request = new AdminCapitalConfigRequest();
        request.setCapitalTotal(BigDecimal.valueOf(1_000_000L));
        request.setBuySplitRules(List.of(20, 20, 20));
        request.setSellSplitRules(List.of(30, 30, 40));

        IllegalArgumentException e = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> paperTradeConfigService.applyCapitalConfig(request));
        Assertions.assertTrue(e.getMessage().contains("buy_split_rules"));
    }

    @Test
    void applyCapitalConfigRejectsInvalidSellSplitSum() {
        AdminCapitalConfigRequest request = new AdminCapitalConfigRequest();
        request.setCapitalTotal(BigDecimal.valueOf(1_000_000L));
        request.setBuySplitRules(List.of(30, 30, 40));
        request.setSellSplitRules(List.of(10, 20, 30));

        IllegalArgumentException e = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> paperTradeConfigService.applyCapitalConfig(request));
        Assertions.assertTrue(e.getMessage().contains("sell_split_rules"));
    }

    @Test
    void applyCapitalConfigRejectsNonPositiveValues() {
        AdminCapitalConfigRequest request = new AdminCapitalConfigRequest();
        request.setCapitalTotal(BigDecimal.valueOf(1_000_000L));
        request.setBuySplitRules(List.of(30, 0, 70));
        request.setSellSplitRules(List.of(30, 30, 40));

        Assertions.assertThrows(IllegalArgumentException.class, () -> paperTradeConfigService.applyCapitalConfig(request));
    }
}


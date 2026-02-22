package com.wangbyul.gnd.api.service.signal;

import com.wangbyul.gnd.api.config.RiskPolicyProperties;
import com.wangbyul.gnd.api.dto.PaperTradeRiskDto;
import com.wangbyul.gnd.api.service.StrategyConfigService;
import com.wangbyul.gnd.api.service.signal.model.RiskDecision;
import com.wangbyul.gnd.core.domain.AssetType;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.PaperTradePositionEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.PaperTradePositionRepository;
import com.wangbyul.gnd.core.repository.TradingSignalRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * RiskPolicyService 리스크 판정 테스트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
class RiskPolicyServiceTest {

    private PaperTradePositionRepository positionRepository;
    private AssetUniverseRepository assetRepository;
    private StrategyConfigService strategyConfigService;
    private ReanalysisLockService reanalysisLockService;
    private RiskPolicyService riskPolicyService;

    @BeforeEach
    void setUp() {
        positionRepository = Mockito.mock(PaperTradePositionRepository.class);
        assetRepository = Mockito.mock(AssetUniverseRepository.class);
        strategyConfigService = Mockito.mock(StrategyConfigService.class);
        reanalysisLockService = Mockito.mock(ReanalysisLockService.class);
        MarketQuoteSnapshotRepository quoteRepository = Mockito.mock(MarketQuoteSnapshotRepository.class);
        TradingSignalRepository tradingSignalRepository = Mockito.mock(TradingSignalRepository.class);

        RiskPolicyProperties props = new RiskPolicyProperties();
        props.setCapitalTotal(BigDecimal.valueOf(1_000_000L));
        props.setMaxPositionRatioPerAsset(BigDecimal.valueOf(0.30d));
        props.setMaxThemeExposureRatio(BigDecimal.valueOf(0.45d));
        props.setMaxCountryExposureRatio(BigDecimal.valueOf(0.60d));
        props.setMaxOpenPositions(5);
        props.setBuySplitRules(List.of(30, 30, 40));
        props.setSellSplitRules(List.of(30, 30, 40));
        props.setTakeProfitPct(BigDecimal.valueOf(8d));
        props.setStopLossPct(BigDecimal.valueOf(5d));
        props.setReanalysisLockAfterTpSl(true);
        props.setReanalysisLockMinutes(120);

        // 오버라이드는 모두 fallback 사용
        Mockito.when(strategyConfigService.getDecimal(Mockito.anyString(), Mockito.any())).thenAnswer(inv -> inv.getArgument(1));
        Mockito.when(strategyConfigService.getInteger(Mockito.anyString(), Mockito.any())).thenAnswer(inv -> inv.getArgument(1));
        Mockito.when(strategyConfigService.getBoolean(Mockito.anyString(), Mockito.any())).thenAnswer(inv -> inv.getArgument(1));
        Mockito.when(strategyConfigService.getIntegerList(Mockito.anyString(), Mockito.any())).thenAnswer(inv -> inv.getArgument(1));

        riskPolicyService = new RiskPolicyService(
                props,
                positionRepository,
                assetRepository,
                quoteRepository,
                tradingSignalRepository,
                reanalysisLockService,
                strategyConfigService);
    }

    @Test
    void nonBuyActionAlwaysPasses() {
        AssetUniverseEntity asset = asset("A", "KR", "AI");
        RiskDecision decision = riskPolicyService.evaluate(asset, SignalActionType.HOLD, BigDecimal.valueOf(0.2d));
        Assertions.assertTrue(decision.allowed());
    }

    @Test
    void buyLockBlocksBuy() {
        AssetUniverseEntity asset = asset("A", "KR", "AI");
        Mockito.when(reanalysisLockService.isBuyLocked("A")).thenReturn(true);
        RiskDecision decision = riskPolicyService.evaluate(asset, SignalActionType.BUY_CANDIDATE, BigDecimal.valueOf(0.2d));
        Assertions.assertFalse(decision.allowed());
        Assertions.assertEquals(SignalActionType.BUY_LOCK, decision.finalAction());
    }

    @Test
    void maxOpenPositionViolationBlocksBuy() {
        AssetUniverseEntity asset = asset("A", "KR", "AI");
        Mockito.when(reanalysisLockService.isBuyLocked("A")).thenReturn(false);
        Mockito.when(positionRepository.findAll()).thenReturn(List.of(
                position("A1", 1), position("A2", 1), position("A3", 1), position("A4", 1), position("A5", 1)));
        RiskDecision decision = riskPolicyService.evaluate(asset, SignalActionType.BUY_CANDIDATE, BigDecimal.valueOf(0.2d));
        Assertions.assertFalse(decision.allowed());
        Assertions.assertTrue(decision.blockedReason().contains("동시 보유"));
    }

    @Test
    void assetRatioViolationBlocksBuy() {
        AssetUniverseEntity asset = asset("A", "KR", "AI");
        Mockito.when(reanalysisLockService.isBuyLocked("A")).thenReturn(false);
        Mockito.when(positionRepository.findAll()).thenReturn(List.of(position("A", 5000)));
        Mockito.when(positionRepository.findByAssetCode("A")).thenReturn(Optional.of(position("A", 5000)));
        RiskDecision decision = riskPolicyService.evaluate(asset, SignalActionType.BUY_CANDIDATE, BigDecimal.valueOf(0.9d));
        Assertions.assertFalse(decision.allowed());
        Assertions.assertTrue(decision.blockedReason().contains("종목 비중"));
    }

    @Test
    void themeRatioViolationBlocksBuy() {
        AssetUniverseEntity asset = asset("A", "KR", "AI");
        Mockito.when(reanalysisLockService.isBuyLocked("A")).thenReturn(false);
        Mockito.when(positionRepository.findAll()).thenReturn(List.of(position("A", 1)));
        Mockito.when(positionRepository.findByAssetCode("A")).thenReturn(Optional.of(position("A", 1)));
        Mockito.when(assetRepository.findTop200ByThemeAndActiveTrueOrderByUpdatedAtDesc("AI")).thenReturn(List.of(asset("A", "KR", "AI")));
        RiskDecision decision = riskPolicyService.evaluate(asset, SignalActionType.BUY_CANDIDATE, BigDecimal.valueOf(0.8d));
        Assertions.assertFalse(decision.allowed());
    }

    @Test
    void countryRatioViolationBlocksBuy() {
        AssetUniverseEntity asset = asset("A", "KR", "AI");
        Mockito.when(reanalysisLockService.isBuyLocked("A")).thenReturn(false);
        Mockito.when(positionRepository.findAll()).thenReturn(List.of(position("A", 1)));
        Mockito.when(positionRepository.findByAssetCode("A")).thenReturn(Optional.of(position("A", 1)));
        Mockito.when(assetRepository.findTop200ByThemeAndActiveTrueOrderByUpdatedAtDesc("AI")).thenReturn(List.of());
        Mockito.when(assetRepository.findTop200ByCountryAndActiveTrueOrderByUpdatedAtDesc("KR")).thenReturn(List.of(asset("A", "KR", "AI")));
        RiskDecision decision = riskPolicyService.evaluate(asset, SignalActionType.BUY_CANDIDATE, BigDecimal.valueOf(0.8d));
        Assertions.assertFalse(decision.allowed());
    }

    @Test
    void withinRiskRangeThenAllowed() {
        AssetUniverseEntity asset = asset("A", "KR", "AI");
        Mockito.when(reanalysisLockService.isBuyLocked("A")).thenReturn(false);
        Mockito.when(positionRepository.findAll()).thenReturn(List.of(position("A", 0.3), position("B", 9000)));
        Mockito.when(positionRepository.findByAssetCode("A")).thenReturn(Optional.of(position("A", 0.3)));
        Mockito.when(assetRepository.findTop200ByThemeAndActiveTrueOrderByUpdatedAtDesc("AI")).thenReturn(List.of(asset("A", "KR", "AI")));
        Mockito.when(assetRepository.findTop200ByCountryAndActiveTrueOrderByUpdatedAtDesc("KR")).thenReturn(List.of(asset("A", "KR", "AI")));
        RiskDecision decision = riskPolicyService.evaluate(asset, SignalActionType.BUY_CANDIDATE, BigDecimal.valueOf(0.05));
        Assertions.assertTrue(decision.allowed());
    }

    @Test
    void portfolioRiskReturnsConfiguredValues() {
        Mockito.when(positionRepository.findAll()).thenReturn(List.of());
        PaperTradeRiskDto dto = riskPolicyService.portfolioRisk();
        Assertions.assertEquals(BigDecimal.valueOf(1_000_000L), dto.getCapitalTotal());
        Assertions.assertEquals(5, dto.getMaxOpenPositions());
    }

    @Test
    void buyLockListIsMapped() {
        PaperTradePositionEntity locked = position("A", 1);
        locked.setBuyLock(true);
        locked.setLockReason("TAKE_PROFIT");
        Mockito.when(positionRepository.findTop300ByBuyLockTrueOrderByLockUntilAsc()).thenReturn(List.of(locked));
        Mockito.when(assetRepository.findById("A")).thenReturn(Optional.of(asset("A", "KR", "AI")));
        Assertions.assertEquals(1, riskPolicyService.buyLocks().size());
    }

    private AssetUniverseEntity asset(String code, String country, String theme) {
        AssetUniverseEntity asset = new AssetUniverseEntity();
        asset.setAssetCode(code);
        asset.setAssetName(code + "_NAME");
        asset.setCountry(country);
        asset.setTheme(theme);
        asset.setAssetType(AssetType.STOCK);
        asset.setActive(true);
        return asset;
    }

    private PaperTradePositionEntity position(String assetCode, double amount) {
        PaperTradePositionEntity position = new PaperTradePositionEntity();
        position.setAssetCode(assetCode);
        position.setQuantity(BigDecimal.valueOf(amount));
        position.setAvgPrice(BigDecimal.valueOf(100));
        position.setCurrentPrice(BigDecimal.valueOf(100));
        position.setInvestedAmount(position.getQuantity().multiply(position.getAvgPrice()));
        position.setBuyLock(false);
        position.setAvgDownStage(0);
        return position;
    }
}

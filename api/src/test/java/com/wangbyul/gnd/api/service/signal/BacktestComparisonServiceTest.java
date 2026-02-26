package com.wangbyul.gnd.api.service.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.BacktestPolicyProperties;
import com.wangbyul.gnd.api.config.BacktestValidationMode;
import com.wangbyul.gnd.api.dto.BacktestComparisonDto;
import com.wangbyul.gnd.core.domain.MarketRegimeType;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.StrategyRunEntity;
import com.wangbyul.gnd.core.domain.StrategyRunType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.repository.StrategyRunRepository;
import com.wangbyul.gnd.core.repository.TradingSignalRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * BacktestComparisonService 테스트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
class BacktestComparisonServiceTest {

    private TradingSignalRepository tradingSignalRepository;
    private TradingSignalEngineService tradingSignalEngineService;
    private StrategyRunRepository strategyRunRepository;
    private BacktestPolicyProperties backtestPolicyProperties;
    private BacktestComparisonService backtestComparisonService;

    @BeforeEach
    void setUp() {
        tradingSignalRepository = Mockito.mock(TradingSignalRepository.class);
        tradingSignalEngineService = Mockito.mock(TradingSignalEngineService.class);
        strategyRunRepository = Mockito.mock(StrategyRunRepository.class);

        backtestPolicyProperties = new BacktestPolicyProperties();
        backtestPolicyProperties.setValidationMode(BacktestValidationMode.TRAIN_TEST_SPLIT);
        backtestPolicyProperties.setOutOfSampleRequired(true);
        backtestPolicyProperties.setMinimumTradeCountThreshold(2);
        backtestPolicyProperties.setTrainSplitRatio(BigDecimal.valueOf(0.5d));
        backtestPolicyProperties.setWalkForwardTrainSize(2);
        backtestPolicyProperties.setWalkForwardTestSize(1);
        backtestPolicyProperties.setWalkForwardStepSize(1);
        backtestPolicyProperties.setMaxRows(200);

        Mockito.when(strategyRunRepository.save(ArgumentMatchers.any(StrategyRunEntity.class)))
                .thenAnswer(invocation -> {
                    StrategyRunEntity entity = invocation.getArgument(0);
                    if (entity.getId() == null) {
                        entity.setId(100L);
                    }
                    return entity;
                });

        backtestComparisonService = new BacktestComparisonService(
                tradingSignalRepository,
                tradingSignalEngineService,
                strategyRunRepository,
                backtestPolicyProperties,
                new ObjectMapper());
    }

    @Test
    void compareTrainTestSplitSavesReportAndReturnsOosMetrics() {
        Mockito.when(tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                        ArgumentMatchers.eq("KR"),
                        ArgumentMatchers.any(OffsetDateTime.class),
                        ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(
                        signal(0.7, 0.2, 0.8, SignalActionType.BUY_CANDIDATE, OffsetDateTime.now().minusMinutes(4)),
                        signal(0.6, 0.3, 0.65, SignalActionType.SELL_CANDIDATE, OffsetDateTime.now().minusMinutes(3)),
                        signal(0.5, 0.4, 0.55, SignalActionType.HOLD, OffsetDateTime.now().minusMinutes(2)),
                        signal(0.8, 0.1, 0.85, SignalActionType.BUY_CANDIDATE, OffsetDateTime.now().minusMinutes(1))));

        BacktestComparisonDto dto = backtestComparisonService.compare("KR", "", "30d");

        Assertions.assertEquals("TRAIN_TEST_SPLIT", dto.getValidationMode());
        Assertions.assertNotNull(dto.getReportRunId());
        Assertions.assertNotNull(dto.getReportJson());
        Assertions.assertNotNull(dto.getPolicyBeforeReturn());
        Assertions.assertNotNull(dto.getPolicyAfterReturn());
        Mockito.verify(strategyRunRepository).save(ArgumentMatchers.any(StrategyRunEntity.class));
    }

    @Test
    void compareWalkForwardModeBuildsFoldSummary() {
        backtestPolicyProperties.setValidationMode(BacktestValidationMode.WALK_FORWARD);

        Mockito.when(tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                        ArgumentMatchers.eq("KR"),
                        ArgumentMatchers.any(OffsetDateTime.class),
                        ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(
                        signal(0.7, 0.2, 0.8, SignalActionType.BUY_CANDIDATE, OffsetDateTime.now().minusMinutes(6)),
                        signal(0.6, 0.3, 0.65, SignalActionType.HOLD, OffsetDateTime.now().minusMinutes(5)),
                        signal(0.7, 0.25, 0.72, SignalActionType.BUY_CANDIDATE, OffsetDateTime.now().minusMinutes(4)),
                        signal(0.5, 0.35, 0.58, SignalActionType.SELL_CANDIDATE, OffsetDateTime.now().minusMinutes(3)),
                        signal(0.55, 0.4, 0.61, SignalActionType.HOLD, OffsetDateTime.now().minusMinutes(2)),
                        signal(0.8, 0.2, 0.82, SignalActionType.BUY_CANDIDATE, OffsetDateTime.now().minusMinutes(1))));

        BacktestComparisonDto dto = backtestComparisonService.compare("KR", "", "30d");

        Assertions.assertEquals("WALK_FORWARD", dto.getValidationMode());
        Object walkForward = dto.getReportJson().get("walk_forward");
        Assertions.assertNotNull(walkForward);
        Assertions.assertTrue(walkForward.toString().contains("fold"));
    }

    @Test
    void compareAppliesMinimumTradeCountWarning() {
        backtestPolicyProperties.setMinimumTradeCountThreshold(10);

        Mockito.when(tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                        ArgumentMatchers.eq("KR"),
                        ArgumentMatchers.any(OffsetDateTime.class),
                        ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(
                        signal(0.6, 0.4, 0.51, SignalActionType.HOLD, OffsetDateTime.now().minusMinutes(3)),
                        signal(0.6, 0.4, 0.52, SignalActionType.HOLD, OffsetDateTime.now().minusMinutes(2)),
                        signal(0.6, 0.4, 0.53, SignalActionType.HOLD, OffsetDateTime.now().minusMinutes(1))));

        BacktestComparisonDto dto = backtestComparisonService.compare("KR", "", "30d");

        Assertions.assertTrue(Boolean.TRUE.equals(dto.getMinimumTradeCountWarning()));
        Assertions.assertTrue(dto.getWarnings().stream().anyMatch(w -> w.contains("minimum_trade_count_below_threshold")));
    }

    @Test
    void compareDetectsLookAheadViolationWhenFutureBlockedButActionable() {
        TradingSignalEntity row = signal(0.7, 0.2, 0.8, SignalActionType.BUY_CANDIDATE, OffsetDateTime.now().minusMinutes(1));
        row.setBlockedReason("FUTURE_DATA_BLOCKED");

        Mockito.when(tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                        ArgumentMatchers.eq("KR"),
                        ArgumentMatchers.any(OffsetDateTime.class),
                        ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(row));

        BacktestComparisonDto dto = backtestComparisonService.compare("KR", "", "30d");

        Assertions.assertTrue(dto.getLookAheadViolationCount() >= 1);
    }

    @Test
    void compareTriggersGenerationWhenNoRows() {
        Mockito.when(tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                        ArgumentMatchers.eq("KR"),
                        ArgumentMatchers.any(OffsetDateTime.class),
                        ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(signal(0.7, 0.2, 0.8, SignalActionType.BUY_CANDIDATE, OffsetDateTime.now().minusMinutes(1))));

        backtestComparisonService.compare("KR", "", "30d");

        Mockito.verify(tradingSignalEngineService).generateSignals(
                ArgumentMatchers.eq("KR"),
                ArgumentMatchers.eq(""),
                ArgumentMatchers.eq(40),
                ArgumentMatchers.any());
    }

    @Test
    void getReportsReturnsJsonSummaryFromStoredRuns() {
        StrategyRunEntity run = new StrategyRunEntity();
        run.setId(900L);
        run.setRunType(StrategyRunType.BACKTEST);
        run.setScopeCountry("KR");
        run.setScopeTheme("AI");
        run.setResultSummaryJson("{\"validation\":{\"mode\":\"TRAIN_TEST_SPLIT\"},\"compare\":{\"fusion_return\":0.1}}");
        run.setStartedAt(OffsetDateTime.now().minusMinutes(10));
        run.setEndedAt(OffsetDateTime.now().minusMinutes(9));
        run.setProcessedCount(100);
        run.setCreatedSignalCount(30);
        Mockito.when(strategyRunRepository.findByRunTypeAndScopeCountryOrderByStartedAtDesc(
                        ArgumentMatchers.eq(StrategyRunType.BACKTEST),
                        ArgumentMatchers.eq("KR"),
                        ArgumentMatchers.any()))
                .thenReturn(new PageImpl<>(List.of(run)));

        var reports = backtestComparisonService.getReports("KR", "AI", 20);

        Assertions.assertEquals(1, reports.size());
        Assertions.assertTrue(reports.get(0).getResultSummaryJson().containsKey("validation"));
    }

    private TradingSignalEntity signal(
            double good,
            double bad,
            double combined,
            SignalActionType action,
            OffsetDateTime generatedAt) {
        TradingSignalEntity entity = new TradingSignalEntity();
        entity.setCountry("KR");
        entity.setTheme("AI");
        entity.setGoodNewsProbability(BigDecimal.valueOf(good));
        entity.setBadNewsProbability(BigDecimal.valueOf(bad));
        entity.setChartConfidence(BigDecimal.valueOf(0.6d));
        entity.setCombinedConfidence(BigDecimal.valueOf(combined));
        entity.setAction(action);
        entity.setMarketRegime(MarketRegimeType.MIXED);
        entity.setReanalysisLockRequired(false);
        entity.setAvgDownAllowed(true);
        entity.setGeneratedAt(generatedAt);
        return entity;
    }
}

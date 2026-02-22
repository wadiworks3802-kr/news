package com.wangbyul.gnd.api.service.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.dto.TradingSignalViewDto;
import com.wangbyul.gnd.api.service.SystemFeatureToggleService;
import com.wangbyul.gnd.api.service.signal.panel.ChartResponseStrategyService;
import com.wangbyul.gnd.api.service.signal.panel.DiscoveryStrategyService;
import com.wangbyul.gnd.api.service.signal.panel.ScalpStrategyService;
import com.wangbyul.gnd.api.service.signal.panel.SwingStrategyService;
import com.wangbyul.gnd.core.domain.AssetSelectionSourceType;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.domain.UniverseLayerType;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.StrategyRunRepository;
import com.wangbyul.gnd.core.repository.TradingSignalRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 전략 패널 조회 시 유니버스 중복억제/메타 확장 동작 검증.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@ExtendWith(MockitoExtension.class)
class TradingSignalEngineServicePanelSelectionTest {

    @Mock
    private AssetUniverseRepository assetUniverseRepository;
    @Mock
    private TradingSignalRepository tradingSignalRepository;
    @Mock
    private StrategyRunRepository strategyRunRepository;
    @Mock
    private ScalpNewsSignalService scalpNewsSignalService;
    @Mock
    private MarketTrendSignalService marketTrendSignalService;
    @Mock
    private ChartPositionStrategyService chartPositionStrategyService;
    @Mock
    private LongTermDiscoveryService longTermDiscoveryService;
    @Mock
    private SignalFusionService signalFusionService;
    @Mock
    private PressureDetectionService pressureDetectionService;
    @Mock
    private RiskPolicyService riskPolicyService;
    @Mock
    private ReanalysisLockService reanalysisLockService;
    @Mock
    private WeeklyContextAnalysisService weeklyContextAnalysisService;
    @Mock
    private TimeAlignmentValidationService timeAlignmentValidationService;
    @Mock
    private SignalAuditLogService signalAuditLogService;
    @Mock
    private SignalReasonBuilder signalReasonBuilder;
    @Mock
    private SystemFeatureToggleService systemFeatureToggleService;

    @Test
    void scalpPanelShouldReduceRepeatedAssetsAndFamiliesAndExposeSelectionMeta() {
        ObjectMapper objectMapper = new ObjectMapper();
        TradingSignalEngineService service = new TradingSignalEngineService(
                assetUniverseRepository,
                tradingSignalRepository,
                strategyRunRepository,
                scalpNewsSignalService,
                marketTrendSignalService,
                chartPositionStrategyService,
                longTermDiscoveryService,
                signalFusionService,
                pressureDetectionService,
                riskPolicyService,
                reanalysisLockService,
                weeklyContextAnalysisService,
                timeAlignmentValidationService,
                signalAuditLogService,
                signalReasonBuilder,
                objectMapper,
                new SignalPolicyProperties(),
                systemFeatureToggleService,
                new ScalpStrategyService(objectMapper),
                new SwingStrategyService(objectMapper),
                new ChartResponseStrategyService(objectMapper),
                new DiscoveryStrategyService(objectMapper));

        ReflectionTestUtils.setField(service, "panelMaxSameFamily", 1);
        ReflectionTestUtils.setField(service, "panelMaxSameTheme", 3);
        ReflectionTestUtils.setField(service, "panelCandidateFetchMultiplier", 8);
        ReflectionTestUtils.setField(service, "priorityThemes", List.of("AI", "SEMICONDUCTOR", "ENERGY"));

        OffsetDateTime now = OffsetDateTime.now();
        List<TradingSignalEntity> rows = List.of(
                signal("S1", "ABC1", "KR", "AI", SignalActionType.BUY_CANDIDATE, "1h", now.minusMinutes(1), 0.90, 0.88),
                signal("S2", "ABC1", "KR", "AI", SignalActionType.WATCH, "1h", now.minusMinutes(2), 0.80, 0.70),   // 동일 자산 중복
                signal("S3", "ABC2", "KR", "AI", SignalActionType.BUY_CANDIDATE, "1h", now.minusMinutes(3), 0.85, 0.82), // 동일 패밀리
                signal("S4", "XYZ1", "KR", "AI", SignalActionType.WATCH, "1h", now.minusMinutes(4), 0.70, 0.78));
        List<AssetUniverseEntity> assets = List.of(
                asset("ABC1", "Alpha Core 1", "KR", "AI", "AI", UniverseLayerType.THEME_LEADER, 95, 0.82, 980, true, now.minusMinutes(3)),
                asset("ABC2", "Alpha Core 2", "KR", "AI", "AI", UniverseLayerType.THEME_LEADER, 90, 0.76, 940, true, now.minusMinutes(4)),
                asset("XYZ1", "Xylon Tech", "KR", "AI", "AI", UniverseLayerType.CORE, 88, 0.70, 900, true, now.minusMinutes(5)));

        when(tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                eq("KR"), any(OffsetDateTime.class), any(Pageable.class)))
                        .thenReturn(rows);
        when(assetUniverseRepository.findByCountryAndActiveTrueOrderByDisplayWeightDescSelectionScoreDescUpdatedAtDesc("KR"))
                .thenReturn(assets);

        List<TradingSignalViewDto> result = service.getScalpSignals("KR", "AI", 2);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TradingSignalViewDto::getAssetCode).doesNotHaveDuplicates();
        assertThat(result).extracting(TradingSignalViewDto::getAssetCode)
                .contains("XYZ1")
                .anyMatch(code -> code.equals("ABC1") || code.equals("ABC2"));
        assertThat(result).extracting(TradingSignalViewDto::getThemeCode).containsOnly("AI");
        assertThat(result).allSatisfy(row -> {
            assertThat(row.getCoreThemeFilterApplied()).isTrue();
            assertThat(row.getDedupApplied()).isTrue();
            assertThat(row.getSelectionReason()).contains("panel=scalp");
        });
    }

    private TradingSignalEntity signal(
            String id,
            String assetCode,
            String country,
            String theme,
            SignalActionType action,
            String signalWindow,
            OffsetDateTime generatedAt,
            double combinedConfidence,
            double panelScore) {
        TradingSignalEntity entity = new TradingSignalEntity();
        entity.setId(id);
        entity.setAssetCode(assetCode);
        entity.setCountry(country);
        entity.setTheme(theme);
        entity.setAction(action);
        entity.setSignalWindow(signalWindow);
        entity.setGeneratedAt(generatedAt);
        entity.setCombinedConfidence(BigDecimal.valueOf(combinedConfidence));
        entity.setScalpSignalScore(BigDecimal.valueOf(panelScore));
        entity.setSwingSignalScore(BigDecimal.valueOf(panelScore));
        entity.setDiscoveryScore(BigDecimal.valueOf(panelScore));
        return entity;
    }

    private AssetUniverseEntity asset(
            String assetCode,
            String assetName,
            String country,
            String theme,
            String themeCode,
            UniverseLayerType layer,
            int selectionScore,
            double diversityScore,
            int displayWeight,
            boolean tradeEnabled,
            OffsetDateTime lastQuoteReceivedAt) {
        AssetUniverseEntity entity = new AssetUniverseEntity();
        entity.setAssetCode(assetCode);
        entity.setAssetName(assetName);
        entity.setCountry(country);
        entity.setTheme(theme);
        entity.setThemeCode(themeCode);
        entity.setActive(true);
        entity.setSelectionSource(AssetSelectionSourceType.THEME_LEADER);
        entity.setUniverseLayer(layer);
        entity.setSelectionScore(BigDecimal.valueOf(selectionScore));
        entity.setDiversityScore(BigDecimal.valueOf(diversityScore));
        entity.setDisplayWeight(displayWeight);
        entity.setIsTradeEnabled(tradeEnabled);
        entity.setDupExposureCooldownMinutes(60);
        entity.setLastQuoteReceivedAt(lastQuoteReceivedAt);
        entity.setLastSignalGeneratedAt(lastQuoteReceivedAt.minusMinutes(1));
        entity.setSelectionReason("source=THEME_LEADER,priority_theme=AI");
        return entity;
    }
}

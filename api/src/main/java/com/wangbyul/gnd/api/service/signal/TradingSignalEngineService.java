package com.wangbyul.gnd.api.service.signal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.dto.PressureAnalysisDto;
import com.wangbyul.gnd.api.dto.SignalDetailDto;
import com.wangbyul.gnd.api.dto.TradingSignalViewDto;
import com.wangbyul.gnd.api.dto.WeeklyContextDto;
import com.wangbyul.gnd.api.service.SystemFeatureToggleService;
import com.wangbyul.gnd.api.service.assistant.AssistantRagService;
import com.wangbyul.gnd.api.service.signal.panel.ChartResponseStrategyService;
import com.wangbyul.gnd.api.service.signal.panel.DiscoveryStrategyService;
import com.wangbyul.gnd.api.service.signal.panel.PanelStrategyEvaluation;
import com.wangbyul.gnd.api.service.signal.panel.ScalpStrategyService;
import com.wangbyul.gnd.api.service.signal.panel.SwingStrategyService;
import com.wangbyul.gnd.api.service.signal.model.ChartPositionResult;
import com.wangbyul.gnd.api.service.signal.model.DiscoveryResult;
import com.wangbyul.gnd.api.service.signal.model.FusionSignalResult;
import com.wangbyul.gnd.api.service.signal.model.MarketTrendSignalResult;
import com.wangbyul.gnd.api.service.signal.model.PressureDetectionResult;
import com.wangbyul.gnd.api.service.signal.model.RiskDecision;
import com.wangbyul.gnd.api.service.signal.model.ScalpSignalResult;
import com.wangbyul.gnd.api.service.signal.model.TimeAlignmentValidationResult;
import com.wangbyul.gnd.api.service.signal.model.WeeklyContextResult;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.StrategyRunEntity;
import com.wangbyul.gnd.core.domain.StrategyRunType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.domain.UniverseLayerType;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.StrategyRunRepository;
import com.wangbyul.gnd.core.repository.TradingSignalRepository;
import java.math.MathContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시그널 엔진 오케스트레이터.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
@Slf4j
public class TradingSignalEngineService {

    private final AssetUniverseRepository assetUniverseRepository;
    private final MarketPriceBarRepository marketPriceBarRepository;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final TradingSignalRepository tradingSignalRepository;
    private final StrategyRunRepository strategyRunRepository;
    private final ScalpNewsSignalService scalpNewsSignalService;
    private final MarketTrendSignalService marketTrendSignalService;
    private final ChartPositionStrategyService chartPositionStrategyService;
    private final LongTermDiscoveryService longTermDiscoveryService;
    private final SignalFusionService signalFusionService;
    private final PressureDetectionService pressureDetectionService;
    private final RiskPolicyService riskPolicyService;
    private final ReanalysisLockService reanalysisLockService;
    private final WeeklyContextAnalysisService weeklyContextAnalysisService;
    private final TimeAlignmentValidationService timeAlignmentValidationService;
    private final SignalAuditLogService signalAuditLogService;
    private final SignalReasonBuilder signalReasonBuilder;
    private final ObjectMapper objectMapper;
    private final SignalPolicyProperties signalPolicyProperties;
    private final SystemFeatureToggleService systemFeatureToggleService;
    private final AssistantRagService assistantRagService;
    private final ScalpStrategyService scalpStrategyService;
    private final SwingStrategyService swingStrategyService;
    private final ChartResponseStrategyService chartResponseStrategyService;
    private final DiscoveryStrategyService discoveryStrategyService;

    @Value("${app.universe.panel-max-same-family:1}")
    private int panelMaxSameFamily;

    @Value("${app.universe.panel-max-same-theme:2}")
    private int panelMaxSameTheme;

    @Value("${app.universe.panel-candidate-fetch-multiplier:8}")
    private int panelCandidateFetchMultiplier;

    @Value("${app.universe.panel-max-exposure-per-24h:3}")
    private int panelMaxExposurePer24h;

    @Value("${app.universe.panel-exposure-write-enabled:false}")
    private boolean panelExposureWriteEnabled;

    @Value("${app.universe.priority-themes:RESOURCE,DEFENSE,SPACE,AI,SEMICONDUCTOR,ROBOTICS,ENERGY}")
    private List<String> priorityThemes;

    public TradingSignalEngineService(
            AssetUniverseRepository assetUniverseRepository,
            MarketPriceBarRepository marketPriceBarRepository,
            MarketQuoteSnapshotRepository marketQuoteSnapshotRepository,
            TradingSignalRepository tradingSignalRepository,
            StrategyRunRepository strategyRunRepository,
            ScalpNewsSignalService scalpNewsSignalService,
            MarketTrendSignalService marketTrendSignalService,
            ChartPositionStrategyService chartPositionStrategyService,
            LongTermDiscoveryService longTermDiscoveryService,
            SignalFusionService signalFusionService,
            PressureDetectionService pressureDetectionService,
            RiskPolicyService riskPolicyService,
            ReanalysisLockService reanalysisLockService,
            WeeklyContextAnalysisService weeklyContextAnalysisService,
            TimeAlignmentValidationService timeAlignmentValidationService,
            SignalAuditLogService signalAuditLogService,
            SignalReasonBuilder signalReasonBuilder,
            ObjectMapper objectMapper,
            SignalPolicyProperties signalPolicyProperties,
            SystemFeatureToggleService systemFeatureToggleService,
            AssistantRagService assistantRagService,
            ScalpStrategyService scalpStrategyService,
            SwingStrategyService swingStrategyService,
            ChartResponseStrategyService chartResponseStrategyService,
            DiscoveryStrategyService discoveryStrategyService) {
        this.assetUniverseRepository = assetUniverseRepository;
        this.marketPriceBarRepository = marketPriceBarRepository;
        this.marketQuoteSnapshotRepository = marketQuoteSnapshotRepository;
        this.tradingSignalRepository = tradingSignalRepository;
        this.strategyRunRepository = strategyRunRepository;
        this.scalpNewsSignalService = scalpNewsSignalService;
        this.marketTrendSignalService = marketTrendSignalService;
        this.chartPositionStrategyService = chartPositionStrategyService;
        this.longTermDiscoveryService = longTermDiscoveryService;
        this.signalFusionService = signalFusionService;
        this.pressureDetectionService = pressureDetectionService;
        this.riskPolicyService = riskPolicyService;
        this.reanalysisLockService = reanalysisLockService;
        this.weeklyContextAnalysisService = weeklyContextAnalysisService;
        this.timeAlignmentValidationService = timeAlignmentValidationService;
        this.signalAuditLogService = signalAuditLogService;
        this.signalReasonBuilder = signalReasonBuilder;
        this.objectMapper = objectMapper;
        this.signalPolicyProperties = signalPolicyProperties;
        this.systemFeatureToggleService = systemFeatureToggleService;
        this.assistantRagService = assistantRagService;
        this.scalpStrategyService = scalpStrategyService;
        this.swingStrategyService = swingStrategyService;
        this.chartResponseStrategyService = chartResponseStrategyService;
        this.discoveryStrategyService = discoveryStrategyService;
    }

    @Transactional
    public List<TradingSignalEntity> generateSignals(String country, String theme, int limit, StrategyRunType runType) {
        if (!isEngineEnabled(country, theme, runType)) {
            StrategyRunEntity blockedRun = new StrategyRunEntity();
            blockedRun.setRunType(runType);
            blockedRun.setScopeCountry(country);
            blockedRun.setScopeTheme(theme);
            blockedRun.setStartedAt(OffsetDateTime.now());
            blockedRun.setEndedAt(OffsetDateTime.now());
            blockedRun.setStatus(com.wangbyul.gnd.core.domain.JobStatus.FAILED);
            blockedRun.setTraceId(traceId());
            blockedRun.setProcessedCount(0);
            blockedRun.setCreatedSignalCount(0);
            blockedRun.setReportJson("{\"blocked\":\"feature-toggle\"}");
            blockedRun.setResultSummaryJson("{\"blocked\":\"feature-toggle\"}");
            strategyRunRepository.save(blockedRun);
            return List.of();
        }

        StrategyRunEntity run = new StrategyRunEntity();
        run.setRunType(runType);
        run.setScopeCountry(country);
        run.setScopeTheme(theme);
        run.setStartedAt(OffsetDateTime.now());
        run.setStatus(com.wangbyul.gnd.core.domain.JobStatus.RUNNING);
        run.setTraceId(traceId());
        run = strategyRunRepository.save(run);

        List<AssetUniverseEntity> assets = resolveAssets(country, theme, limit);
        int created = 0;
        for (AssetUniverseEntity asset : assets) {
            TradingSignalEntity saved = computeAndSave(asset, runType);
            if (saved != null) {
                created++;
            }
        }

        run.setEndedAt(OffsetDateTime.now());
        run.setProcessedCount(assets.size());
        run.setCreatedSignalCount(created);
        run.setStatus(com.wangbyul.gnd.core.domain.JobStatus.SUCCESS);
        run.setReportJson("{\"processed\":" + assets.size() + ",\"created\":" + created + "}");
        run.setResultSummaryJson("{\"processed\":" + assets.size() + ",\"created\":" + created + "}");
        strategyRunRepository.save(run);

        return tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                country,
                OffsetDateTime.now().minusMinutes(30),
                PageRequest.of(0, Math.max(1, limit)));
    }

    private boolean isEngineEnabled(String country, String theme, StrategyRunType runType) {
        boolean generationEnabled = systemFeatureToggleService.isFeatureEnabled("SIGNAL_GENERATION", country, theme, null);
        if (!generationEnabled) {
            return false;
        }
        String engineKey = switch (runType) {
            case SCALP -> "SCALP_ENGINE";
            case SWING -> "SWING_ENGINE";
            case POSITION -> "POSITION_ENGINE";
            case DISCOVERY -> "DISCOVERY_ENGINE";
            case BACKTEST -> "BACKTEST_ENGINE";
            default -> null;
        };
        if (engineKey == null) {
            return true;
        }
        return systemFeatureToggleService.isFeatureEnabled(engineKey, country, theme, null);
    }

    @Transactional
    public List<TradingSignalViewDto> getScalpSignals(String country, String theme, int limit) {
        ensureRecentSignals(country, theme, Math.max(limit, 20), StrategyRunType.SCALP);
        List<TradingSignalViewDto> rows = queryPanelSignals(
                country,
                theme,
                scalpStrategyService.allowedActions(),
                limit,
                PanelType.SCALP,
                scalpStrategyService.signalWindow());
        if (!rows.isEmpty()) {
            return rows;
        }
        return fallbackScalpSignals(country, theme, limit);
    }

    @Transactional
    public List<TradingSignalViewDto> getSwingSignals(String country, String theme, int limit) {
        ensureRecentSignals(country, theme, Math.max(limit, 20), StrategyRunType.SWING);
        return queryPanelSignals(country, theme, swingStrategyService.allowedActions(), limit, PanelType.SWING, swingStrategyService.signalWindow());
    }

    public TradingSignalViewDto getPositionSignal(String assetCode) {
        tradingSignalRepository.findTop1ByAssetCodeAndSignalWindowOrderByGeneratedAtDesc(assetCode, chartResponseStrategyService.signalWindow())
                .orElseGet(() -> {
                    AssetUniverseEntity asset = assetUniverseRepository.findById(assetCode)
                            .orElseThrow(() -> new IllegalArgumentException("asset not found: " + assetCode));
                    return computeAndSave(asset, StrategyRunType.POSITION);
                });
        TradingSignalEntity signal = tradingSignalRepository.findTop1ByAssetCodeAndSignalWindowOrderByGeneratedAtDesc(assetCode, chartResponseStrategyService.signalWindow())
                .or(() -> tradingSignalRepository.findTop1ByAssetCodeOrderByGeneratedAtDesc(assetCode))
                .orElseThrow(() -> new IllegalArgumentException("signal not found: " + assetCode));
        AssetUniverseEntity asset = assetUniverseRepository.findById(signal.getAssetCode()).orElse(null);
        PanelStrategyEvaluation eval = chartResponseStrategyService.evaluate(signal, asset);
        return toViewDto(signal, asset, PanelSelectionMeta.forPosition(eval));
    }

    @Transactional
    public List<TradingSignalViewDto> getDiscoverySignals(String country, String theme, int limit) {
        ensureRecentSignals(country, theme, Math.max(limit, 30), StrategyRunType.DISCOVERY);
        return queryPanelSignals(country, theme, discoveryStrategyService.allowedActions(), limit, PanelType.DISCOVERY, discoveryStrategyService.signalWindow());
    }

    public WeeklyContextDto getWeeklyContext(String assetCode) {
        AssetUniverseEntity asset = assetUniverseRepository.findById(assetCode)
                .orElseThrow(() -> new IllegalArgumentException("asset not found: " + assetCode));
        WeeklyContextResult result = weeklyContextAnalysisService.analyze(asset);
        return WeeklyContextDto.builder()
                .assetCode(assetCode)
                .newsCount7d(result.newsCount7d())
                .positiveNewsRatio(result.positiveNewsRatio())
                .negativeNewsRatio(result.negativeNewsRatio())
                .priceTrendScore(result.priceTrendScore())
                .volumeTrendScore(result.volumeTrendScore())
                .volatilityScore(result.volatilityScore())
                .weeklyContextScore(result.weeklyContextScore())
                .build();
    }

    public PressureAnalysisDto getPressureAnalysis(String assetCode) {
        PressureDetectionResult result = pressureDetectionService.analyze(assetCode);
        return PressureAnalysisDto.builder()
                .assetCode(assetCode)
                .sellPressureDetected(result.sellPressureDetected())
                .sellPressureIsNegative(result.sellPressureNegative())
                .buyPressureDetected(result.buyPressureDetected())
                .buyPressureIsPositive(result.buyPressurePositive())
                .volumeRegimeSame(result.volumeRegimeSame())
                .windowBars(result.windowBars())
                .build();
    }

    public SignalDetailDto getSignalDetail(String signalId) {
        return getSignalDetail(signalId, true);
    }

    public SignalDetailDto getSignalDetail(String signalId, boolean includeAssistant) {
        TradingSignalEntity signal = tradingSignalRepository.findById(signalId)
                .orElseThrow(() -> new IllegalArgumentException("signal not found: " + signalId));
        AssetUniverseEntity asset = assetUniverseRepository.findById(signal.getAssetCode()).orElse(null);
        List<String> riskChecks = parseRiskChecks(signal.getRiskChecks());
        PressureAnalysisDto pressure = getPressureAnalysis(signal.getAssetCode());
        Map<String, Object> scalpBreakdown = parseJsonMap(signal.getProbabilityReasonBreakdownJson());
        Map<String, Object> reasonRoot = parseJsonMap(signal.getReasonJson());
        List<String> missingRequirements = stringListField(scalpBreakdown, "missing_requirements");
        List<String> changeConditions = stringListField(scalpBreakdown, "change_conditions");
        String explainText = firstNonBlank(signal.getExplainText(), textField(scalpBreakdown, "explain_text"));
        String decisionWhy = buildDecisionWhy(signal, explainText, riskChecks, missingRequirements);
        Map<String, Object> strategyEvidence = buildStrategyEvidence(signal, asset, null, reasonRoot, scalpBreakdown);
        List<Map<String, Object>> strategyComparison = buildStrategyComparison(signal.getAssetCode(), asset);
        Map<String, Object> riskGuidance = buildRiskGuidance(signal);
        AssistantRagService.SignalDetailAssistResult assistantAssist = assistantRagService.assistSignalDetail(
                signal,
                asset,
                riskChecks,
                scalpBreakdown,
                includeAssistant);
        String ragContextRefsJson = firstNonBlank(
                assistantAssist == null ? null : assistantAssist.ragContextRefsJson(),
                signal.getRagContextRefsJson(),
                "[]");
        return SignalDetailDto.builder()
                .signalId(signal.getId())
                .assetCode(signal.getAssetCode())
                .assetName(asset == null ? "-" : asset.getAssetName())
                .action(signal.getAction())
                .goodNewsProbability(signal.getGoodNewsProbability())
                .badNewsProbability(signal.getBadNewsProbability())
                .newsConfidence(firstNonNullBigDecimal(scale(signal.getNewsConfidence()), decimalField(scalpBreakdown, "news_confidence")))
                .weeklyContextScore(signal.getWeeklyContextScore())
                .combinedConfidence(signal.getCombinedConfidence())
                .analysisState(textField(scalpBreakdown, "analysis_state"))
                .dataState(textField(scalpBreakdown, "data_state"))
                .probabilityReasonBreakdownJson(signal.getProbabilityReasonBreakdownJson())
                .pressureReasonJson(signal.getPressureReasonJson())
                .topPositiveFactorsJson(signal.getTopPositiveFactorsJson())
                .topNegativeFactorsJson(signal.getTopNegativeFactorsJson())
                .explainText(explainText)
                .newsAlignmentResultJson(signal.getNewsAlignmentResultJson())
                .dataFreshnessJson(signal.getDataFreshnessJson())
                .dedupResultJson(signal.getDedupResultJson())
                .ragContextRefsJson(ragContextRefsJson)
                .assistantRag(assistantAssist == null ? null : assistantAssist.insight())
                .strategyEvidence(strategyEvidence)
                .strategyComparison(strategyComparison)
                .riskGuidance(riskGuidance)
                .newsEvidence(buildNewsEvidence(signal, scalpBreakdown))
                .chartEvidence(buildChartEvidence(signal, reasonRoot))
                .priceEvidence(buildPriceEvidence(signal, scalpBreakdown))
                .volumeEvidence(buildVolumeEvidence(signal, scalpBreakdown))
                .riskEvidence(buildRiskEvidence(signal, riskChecks))
                .decisionWhy(decisionWhy)
                .missingRequirements(missingRequirements)
                .changeConditions(changeConditions)
                .pressureAnalysis(pressure)
                .riskChecks(riskChecks)
                .blockedReason(signal.getBlockedReason())
                .reasonJson(signal.getReasonJson())
                .generatedAt(signal.getGeneratedAt())
                .build();
    }

    /**
     * API 메타에서 사용하는 테마 코드 정규화 결과를 노출한다.
     * 기존 응답 호환을 위해 필수 로직에는 영향 주지 않고 메타/진단 용도로만 사용한다.
     */
    public String normalizeThemeForApi(String theme) {
        return normalizeThemeCode(theme);
    }

    private List<AssetUniverseEntity> resolveAssets(String country, String theme, int limit) {
        List<AssetUniverseEntity> assets;
        if (theme == null || theme.isBlank()) {
            assets = assetUniverseRepository.findByCountryAndActiveTrueOrderByDisplayWeightDescSelectionScoreDescUpdatedAtDesc(country);
            if (assets.isEmpty()) {
                assets = assetUniverseRepository.findTop200ByCountryAndActiveTrueOrderByUpdatedAtDesc(country);
            }
        } else {
            String normalizedThemeCode = normalizeThemeCode(theme);
            assets = assetUniverseRepository.findByCountryAndThemeAndActiveTrueOrderByDisplayWeightDescSelectionScoreDescUpdatedAtDesc(
                    country,
                    theme);
            if (assets.isEmpty()) {
                assets = assetUniverseRepository.findByCountryAndThemeCodeAndActiveTrueOrderByDisplayWeightDescSelectionScoreDescUpdatedAtDesc(
                        country,
                        normalizedThemeCode);
            }
            if (assets.isEmpty()) {
                assets = assetUniverseRepository.findTop200ByCountryAndThemeAndActiveTrueOrderByUpdatedAtDesc(country, theme);
            }
            if (assets.isEmpty()) {
                assets = assetUniverseRepository.findTop200ByCountryAndThemeCodeAndActiveTrueOrderByUpdatedAtDesc(country, normalizedThemeCode);
            }
        }
        return assets.stream().limit(Math.max(1, limit)).toList();
    }

    private void ensureRecentSignals(String country, String theme, int limit, StrategyRunType runType) {
        String signalWindow = resolveSignalWindow(runType);
        List<TradingSignalEntity> existing = tradingSignalRepository.findByCountryAndSignalWindowAndGeneratedAtAfterOrderByGeneratedAtDesc(
                country,
                signalWindow,
                OffsetDateTime.now().minusMinutes(20),
                PageRequest.of(0, 50));
        boolean exists = existing.stream()
                .anyMatch(signal -> theme == null || theme.isBlank() || matchesThemeFilter(theme, signal, null));
        if (!exists) {
            try {
                generateSignals(country, theme, limit, runType);
            } catch (RuntimeException e) {
                log.warn(
                        "ensureRecentSignals generation skipped runType={} country={} theme={} reason={} trace_id={}",
                        runType == null ? "UNKNOWN" : runType.name(),
                        safeString(country, "ALL"),
                        safeString(theme, "ALL"),
                        e.getMessage(),
                        traceId());
            }
        }
    }

    private TradingSignalEntity computeAndSave(AssetUniverseEntity asset, StrategyRunType runType) {
        OffsetDateTime signalTime = OffsetDateTime.now();
        TimeAlignmentValidationResult alignment = timeAlignmentValidationService.validateForSignal(asset, signalTime);
        ScalpSignalResult scalp = scalpNewsSignalService.analyze(
                asset,
                signalTime,
                signalPolicyWindowMinutes());
        MarketTrendSignalResult trend = marketTrendSignalService.analyze(asset);
        PressureDetectionResult pressure = pressureDetectionService.analyze(asset.getAssetCode());
        WeeklyContextResult weekly = weeklyContextAnalysisService.analyze(asset);
        ChartPositionResult chart = chartPositionStrategyService.analyze(asset, scalp.badNewsProbability());
        DiscoveryResult discovery = longTermDiscoveryService.analyze(asset);
        boolean lockActive = reanalysisLockService.isBuyLocked(asset.getAssetCode());

        FusionSignalResult fusion = signalFusionService.fuse(scalp, trend, chart, pressure, weekly, lockActive);
        SignalActionType decisionBeforeRisk = alignment.futureDataDetected() ? SignalActionType.WATCH : fusion.action();
        BigDecimal combinedBeforeRisk = clamp01(scale(fusion.combinedConfidence()));
        BigDecimal combinedAfterAlignment = clamp01(scale(combinedBeforeRisk.multiply(BigDecimal.ONE.subtract(alignment.penaltyRate()))));
        BigDecimal requestRatio = chart.avgDownNextBuyRatio() != null && chart.avgDownNextBuyRatio().compareTo(BigDecimal.ZERO) > 0
                ? chart.avgDownNextBuyRatio()
                : defaultRequestRatio();
        RiskDecision riskDecision = riskPolicyService.evaluate(asset, decisionBeforeRisk, requestRatio);
        List<String> riskChecksWithAlignment = new ArrayList<>(riskDecision.riskChecks());
        riskChecksWithAlignment.addAll(alignment.warnings());
        String blockedReason = mergeBlockedReason(riskDecision.blockedReason(), alignment);

        TradingSignalEntity signal = new TradingSignalEntity();
        signal.setAssetCode(asset.getAssetCode());
        signal.setCountry(asset.getCountry());
        signal.setTheme(asset.getTheme());
        signal.setSignalWindow(resolveSignalWindow(runType));
        signal.setAction(riskDecision.finalAction());
        signal.setMarketRegime(trend.marketRegime());
        signal.setGoodNewsProbability(scalp.goodNewsProbability());
        signal.setBadNewsProbability(scalp.badNewsProbability());
        signal.setNewsConfidence(scalp.newsConfidence());
        signal.setProbabilityReasonBreakdownJson(scalp.probabilityReasonBreakdownJson());
        Map<String, Object> scalpBreakdown = parseJsonMap(scalp.probabilityReasonBreakdownJson());
        signal.setTopPositiveFactorsJson(toJsonValue(mapField(scalpBreakdown, "top_positive_factors", List.of())));
        signal.setTopNegativeFactorsJson(toJsonValue(mapField(scalpBreakdown, "top_negative_factors", List.of())));
        signal.setExplainText(resolveSignalExplainText(riskDecision.finalAction(), scalpBreakdown, riskDecision, alignment));
        signal.setNewsAlignmentResultJson(toJsonValue(mapField(scalpBreakdown, "news_alignment_result", Map.of())));
        signal.setDataFreshnessJson(toJsonValue(mapField(scalpBreakdown, "data_freshness", Map.of())));
        signal.setDedupResultJson(toJsonValue(mapField(scalpBreakdown, "dedup_result", Map.of())));
        signal.setRagContextRefsJson(toJsonValue(mapField(scalpBreakdown, "rag_context_refs", List.of())));
        signal.setChartConfidence(chart.chartConfidence());
        signal.setCombinedConfidence(combinedAfterAlignment);
        signal.setWeeklyContextScore(weekly.weeklyContextScore());
        signal.setScalpSignalScore(scalp.scalpSignalScore());
        signal.setSwingSignalScore(trend.swingSignalScore());
        signal.setPositionManagementSignal(chart.positionManagementSignal());
        signal.setDiscoveryScore(discovery.discoveryScore());
        signal.setSellPressureDetected(pressure.sellPressureDetected());
        signal.setSellPressureIsNegative(pressure.sellPressureNegative());
        signal.setBuyPressureDetected(pressure.buyPressureDetected());
        signal.setBuyPressureIsPositive(pressure.buyPressurePositive());
        signal.setVolumeRegimeSame(pressure.volumeRegimeSame());
        signal.setPressureReasonJson(pressure.pressureReasonJson());
        signal.setAvgDownAllowed(chart.avgDownAllowed());
        signal.setAvgDownStage(chart.avgDownStage());
        signal.setAvgDownReason(chart.avgDownReason());
        signal.setAvgDownNextBuyRatio(chart.avgDownNextBuyRatio());
        signal.setReanalysisLockRequired(lockActive || riskDecision.finalAction() == SignalActionType.BUY_LOCK);
        signal.setReanalysisLockUntil(lockActive ? OffsetDateTime.now().plusMinutes(riskPolicyService.effectiveReanalysisLockMinutes()) : null);
        signal.setRiskChecks(toJsonArray(riskChecksWithAlignment));
        signal.setBlockedReason(blockedReason);
        signal.setModelVersion("policy-v7-rule-v1");
        signal.setTraceId(traceId());
        signal.setReasonJson(signalReasonBuilder.build(scalp, trend, chart, discovery, pressure, weekly, fusion, riskDecision));

        TradingSignalEntity saved = tradingSignalRepository.save(signal);
        asset.setLastSignalGeneratedAt(saved.getGeneratedAt() == null ? OffsetDateTime.now() : saved.getGeneratedAt());
        if (asset.getThemeCode() == null || asset.getThemeCode().isBlank()) {
            asset.setThemeCode(normalizeThemeCode(asset.getTheme()));
        }
        assetUniverseRepository.save(asset);
        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("asset_code", asset.getAssetCode());
        inputSnapshot.put("country", asset.getCountry());
        inputSnapshot.put("theme", asset.getTheme());
        inputSnapshot.put("signal_time_utc", signalTime);
        inputSnapshot.put("alignment_window_minutes", signalPolicyWindowMinutes());
        inputSnapshot.put("scalp", Map.of(
                "matched_news_count", scalp.matchedNewsCount(),
                "good_news_probability", scale(scalp.goodNewsProbability()),
                "bad_news_probability", scale(scalp.badNewsProbability()),
                "news_confidence", scale(scalp.newsConfidence()),
                "probability_reason_breakdown", parseJsonObject(scalp.probabilityReasonBreakdownJson())));
        inputSnapshot.put("chart", Map.of(
                "chart_confidence", scale(chart.chartConfidence()),
                "long_bias", chart.longBias(),
                "short_bias", chart.shortBias(),
                "trend_breakdown_severe", chart.trendBreakdownSevere(),
                "atr_pct", scale(chart.atrPct()),
                "volume_ratio", scale(chart.volumeRatio()),
                "trend_slope_pct", scale(chart.trendSlopePct())));
        inputSnapshot.put("pressure", Map.of(
                "sell_pressure_ratio", scale(pressure.sellPressureRatio()),
                "buy_pressure_ratio", scale(pressure.buyPressureRatio()),
                "volume_diff_pct", scale(pressure.volumeDiffPct()),
                "volume_regime_same", pressure.volumeRegimeSame(),
                "pressure_reason", parseJsonObject(pressure.pressureReasonJson())));

        Map<String, Object> ruleHits = new LinkedHashMap<>();
        ruleHits.put("probability_calculation_mode", "RULE_V1");
        ruleHits.put("probability_reason_breakdown_json", parseJsonObject(scalp.probabilityReasonBreakdownJson()));
        ruleHits.put("chart_rule_set", "CHART_RULESET_V1");
        ruleHits.put("chart_rule_hits_json", parseJsonObject(chart.chartRuleHitsJson()));
        ruleHits.put("pressure_rule_set", "PRESSURE_RULESET_V1");
        ruleHits.put("pressure_reason_json", parseJsonObject(pressure.pressureReasonJson()));
        ruleHits.put("time_alignment", Map.of(
                "future_data_detected", alignment.futureDataDetected(),
                "invalid_publish_fetch_order_count", alignment.invalidPublishFetchOrderCount(),
                "delayed_translation_count", alignment.delayedTranslationCount(),
                "penalty_rate", alignment.penaltyRate(),
                "warnings", alignment.warnings()));

        Map<String, Object> riskChecks = new LinkedHashMap<>();
        riskChecks.put("risk_policy_checks", riskChecksWithAlignment);
        riskChecks.put("blocked_reason", blockedReason);
        riskChecks.put("decision_before_risk", decisionBeforeRisk);
        riskChecks.put("decision_after_risk", riskDecision.finalAction());
        riskChecks.put("confidence_before", scale(combinedBeforeRisk));
        riskChecks.put("confidence_after", scale(combinedAfterAlignment));

        signalAuditLogService.saveFusionAuditLog(
                saved,
                asset,
                decisionBeforeRisk,
                riskDecision.finalAction(),
                combinedBeforeRisk,
                combinedAfterAlignment,
                inputSnapshot,
                ruleHits,
                riskChecks,
                blockedReason,
                saved.getModelVersion());
        reanalysisLockService.markReanalysisCompleted(asset.getAssetCode());
        return saved;
    }

    private List<TradingSignalViewDto> queryPanelSignals(
            String country,
            String theme,
            List<SignalActionType> actions,
            int limit,
            PanelType panelType,
            String signalWindow) {
        int safeLimit = Math.max(1, limit);
        int fetchSize = Math.max(safeLimit * Math.max(2, panelCandidateFetchMultiplier), safeLimit + 20);
        String normalizedTheme = normalizeThemeCode(theme);
        String normalizedCountry = country == null ? "" : country.trim();
        OffsetDateTime selectedAt = OffsetDateTime.now();
        boolean explicitThemeFilter = normalizedTheme != null && !normalizedTheme.isBlank();

        List<TradingSignalEntity> rows = (theme == null || theme.isBlank())
                ? tradingSignalRepository.findByCountryAndSignalWindowAndActionInOrderByGeneratedAtDesc(
                        normalizedCountry, signalWindow, actions, PageRequest.of(0, Math.min(fetchSize, 300)))
                : tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                                normalizedCountry,
                                OffsetDateTime.now().minusHours(24),
                                PageRequest.of(0, Math.min(fetchSize, 300)))
                        .stream()
                        .filter(signal -> Objects.equals(signalWindow, signal.getSignalWindow()))
                        .filter(signal -> actions.contains(signal.getAction()))
                        .toList();

        if (rows.isEmpty()) {
            return List.of();
        }

        Map<String, AssetUniverseEntity> assetMap = assetUniverseRepository.findByCountryAndActiveTrueOrderByDisplayWeightDescSelectionScoreDescUpdatedAtDesc(
                        normalizedCountry)
                .stream()
                .collect(java.util.stream.Collectors.toMap(AssetUniverseEntity::getAssetCode, a -> a, (a, b) -> a));

        // 최신순 결과에서 자산별 1건만 유지하여 동일 자산의 중복 시그널 노출을 제거한다.
        Map<String, TradingSignalEntity> latestByAsset = new LinkedHashMap<>();
        boolean duplicateSignalRowsFound = false;
        for (TradingSignalEntity row : rows) {
            if (row.getAssetCode() == null || row.getAssetCode().isBlank()) {
                continue;
            }
            if (latestByAsset.containsKey(row.getAssetCode())) {
                duplicateSignalRowsFound = true;
                continue;
            }
            latestByAsset.put(row.getAssetCode(), row);
        }

        int scopeFilteredCount = 0;
        int cooldownFilteredCount = 0;
        int exposureCapFilteredCount = 0;
        int themeFilteredCount = 0;
        List<PanelCandidate> candidates = latestByAsset.values().stream()
                .map(signal -> {
                    AssetUniverseEntity asset = assetMap.get(signal.getAssetCode());
                    if (asset == null) {
                        return null;
                    }
                    if (!matchesThemeFilter(theme, signal, asset)) {
                        return null;
                    }
                    if (!matchesPanelStrategyScope(panelType, asset)) {
                        return null;
                    }
                    if (hasPanelExposureCooldown(asset, selectedAt)) {
                        return null;
                    }
                    if (isPanelExposureLimitExceeded(asset, selectedAt)) {
                        return null;
                    }
                    return toPanelCandidate(panelType, signal, asset, normalizedTheme);
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(PanelCandidate::panelScore).reversed()
                        .thenComparing(c -> c.signal().getGeneratedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        for (TradingSignalEntity signal : latestByAsset.values()) {
            AssetUniverseEntity asset = assetMap.get(signal.getAssetCode());
            if (asset == null) {
                continue;
            }
            if (!matchesThemeFilter(theme, signal, asset)) {
                themeFilteredCount++;
                continue;
            }
            if (!matchesPanelStrategyScope(panelType, asset)) {
                scopeFilteredCount++;
                continue;
            }
            if (hasPanelExposureCooldown(asset, selectedAt)) {
                cooldownFilteredCount++;
                continue;
            }
            if (isPanelExposureLimitExceeded(asset, selectedAt)) {
                exposureCapFilteredCount++;
            }
        }

        List<PanelCandidate> selectedCandidates = new ArrayList<>();
        Set<String> selectedAssetCodes = new HashSet<>();
        Map<String, Integer> familyCount = new HashMap<>();
        Map<String, Integer> themeCount = new HashMap<>();
        boolean dedupApplied = duplicateSignalRowsFound;

        for (PanelCandidate candidate : candidates) {
            if (selectedCandidates.size() >= safeLimit) {
                break;
            }
            TradingSignalEntity signal = candidate.signal();
            AssetUniverseEntity asset = candidate.asset();
            String family = tickerFamily(signal.getAssetCode());
            String themeCode = safeString(asset.getThemeCode(), "N/A");

            if (!selectedAssetCodes.add(signal.getAssetCode())) {
                dedupApplied = true;
                continue;
            }
            if (familyCount.getOrDefault(family, 0) >= Math.max(1, panelMaxSameFamily)) {
                dedupApplied = true;
                continue;
            }
            if (!explicitThemeFilter
                    && themeCount.getOrDefault(themeCode, 0) >= Math.max(1, panelMaxSameTheme)) {
                dedupApplied = true;
                continue;
            }
            familyCount.merge(family, 1, Integer::sum);
            themeCount.merge(themeCode, 1, Integer::sum);
            selectedCandidates.add(candidate);
        }

        // 너무 엄격한 중복억제로 빈 화면 방지: 필터 완화 fallback
        if (selectedCandidates.isEmpty()) {
            List<TradingSignalViewDto> fallback = latestByAsset.values().stream()
                    .filter(signal -> {
                        AssetUniverseEntity asset = assetMap.get(signal.getAssetCode());
                        return asset != null && matchesThemeFilter(theme, signal, asset);
                    })
                    .limit(safeLimit)
                    .map(signal -> toViewDto(signal, assetMap.get(signal.getAssetCode()),
                            PanelSelectionMeta.fallback(panelType, normalizedTheme)))
                    .toList();
            logPanelSelectionWarning(
                    panelType,
                    normalizedCountry,
                    normalizedTheme,
                    "fallback-selected",
                    latestByAsset.size(),
                    candidates.size(),
                    fallback.size(),
                    scopeFilteredCount,
                    cooldownFilteredCount,
                    exposureCapFilteredCount,
                    themeFilteredCount,
                    duplicateSignalRowsFound);
            return fallback;
        }

        recordPanelExposureSafely(selectedCandidates, selectedAt, panelType, normalizedCountry, normalizedTheme);
        logPanelSelectionDuplicatesIfNeeded(
                panelType,
                normalizedCountry,
                normalizedTheme,
                selectedCandidates,
                scopeFilteredCount,
                cooldownFilteredCount,
                exposureCapFilteredCount,
                themeFilteredCount,
                duplicateSignalRowsFound);

        boolean finalDedupApplied = dedupApplied;
        return selectedCandidates.stream()
                .map(candidate -> toViewDto(
                        candidate.signal(),
                        candidate.asset(),
                        candidate.meta() == null
                                ? null
                                : candidate.meta().withDedupApplied(finalDedupApplied)))
                .toList();
    }

    private List<TradingSignalViewDto> fallbackScalpSignals(String country, String theme, int limit) {
        int safeLimit = Math.max(1, limit);
        String normalizedCountry = country == null ? "" : country.trim();
        String normalizedTheme = normalizeThemeCode(theme);
        List<TradingSignalEntity> recent = tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                normalizedCountry,
                OffsetDateTime.now().minusHours(24),
                PageRequest.of(0, Math.min(300, safeLimit * 20)));
        if (recent.isEmpty()) {
            return List.of();
        }
        Map<String, AssetUniverseEntity> assetMap = assetUniverseRepository
                .findByCountryAndActiveTrueOrderByDisplayWeightDescSelectionScoreDescUpdatedAtDesc(normalizedCountry)
                .stream()
                .collect(java.util.stream.Collectors.toMap(AssetUniverseEntity::getAssetCode, a -> a, (a, b) -> a));

        Set<String> seenAssets = new HashSet<>();
        List<TradingSignalViewDto> fallback = new ArrayList<>();
        for (TradingSignalEntity signal : recent) {
            if (signal == null || signal.getAssetCode() == null || signal.getAssetCode().isBlank()) {
                continue;
            }
            if (!scalpStrategyService.allowedActions().contains(signal.getAction())) {
                continue;
            }
            AssetUniverseEntity asset = assetMap.get(signal.getAssetCode());
            if (asset == null || !matchesThemeFilter(theme, signal, asset)) {
                continue;
            }
            if (!seenAssets.add(signal.getAssetCode())) {
                continue;
            }
            fallback.add(toViewDto(signal, asset, PanelSelectionMeta.fallback(PanelType.SCALP, normalizedTheme)));
            if (fallback.size() >= safeLimit) {
                break;
            }
        }
        if (!fallback.isEmpty()) {
            log.warn(
                    "scalp panel fallback applied country={} theme={} selected={} trace_id={}",
                    normalizedCountry,
                    safeString(normalizedTheme, "ALL"),
                    fallback.size(),
                    traceId());
        }
        return fallback;
    }

    private PanelCandidate toPanelCandidate(
            PanelType panelType,
            TradingSignalEntity signal,
            AssetUniverseEntity asset,
            String normalizedThemeFilter) {
        PanelStrategyEvaluation strategyEval = evaluatePanelStrategy(panelType, signal, asset);
        BigDecimal score = scale(strategyEval.panelSignalScore());
        BigDecimal universeScore = scale(asset.getSelectionScore());
        BigDecimal diversity = scale(asset.getDiversityScore());
        BigDecimal displayWeight = BigDecimal.valueOf(asset.getDisplayWeight() == null ? 0 : asset.getDisplayWeight());
        BigDecimal layerBonus = scale(strategyEval.layerBonus());
        BigDecimal stalePenalty = stalePenalty(asset);
        BigDecimal qualityPenalty = strategyEval.qualityDegraded() ? BigDecimal.valueOf(0.06d) : BigDecimal.ZERO;
        BigDecimal priorityBonus = isPriorityTheme(asset.getThemeCode()) ? BigDecimal.valueOf(0.05d) : BigDecimal.ZERO;

        BigDecimal total = score.multiply(BigDecimal.valueOf(0.55d), MathContext.DECIMAL64)
                .add(scale(signal.getCombinedConfidence()).multiply(BigDecimal.valueOf(0.20d), MathContext.DECIMAL64))
                .add(universeScore.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(0.10d), MathContext.DECIMAL64))
                .add(diversity.multiply(BigDecimal.valueOf(0.10d), MathContext.DECIMAL64))
                .add(displayWeight.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(0.05d), MathContext.DECIMAL64))
                .add(layerBonus)
                .add(priorityBonus)
                .subtract(stalePenalty)
                .subtract(qualityPenalty);

        StringBuilder reason = new StringBuilder();
        reason.append("panel=").append(panelType.name().toLowerCase(Locale.ROOT));
        reason.append(",strategy_key=").append(strategyEval.strategyKey());
        reason.append(",signal_window=").append(signal.getSignalWindow());
        reason.append(",panel_score=").append(scale(score));
        reason.append(",combined=").append(scale(signal.getCombinedConfidence()));
        reason.append(",panel_purpose=").append(trimForMeta(strategyEval.panelPurpose(), 40));
        reason.append(",primary_metric=").append(strategyEval.primaryMetricLabel())
                .append(":").append(scale(strategyEval.primaryMetricValue()));
        reason.append(",state=").append(strategyEval.stateBadge());
        reason.append(",universe_score=").append(scale(universeScore));
        reason.append(",layer=").append(asset.getUniverseLayer() == null ? "N/A" : asset.getUniverseLayer().name());
        reason.append(",theme_code=").append(safeString(asset.getThemeCode(), "N/A"));
        reason.append(",priority_theme=").append(isPriorityTheme(asset.getThemeCode()));
        reason.append(",trade_enabled=").append(Boolean.TRUE.equals(asset.getIsTradeEnabled()));
        reason.append(",strategy_scope=").append(safeString(asset.getStrategyScope(), "ALL"));
        reason.append(",panel_exposure_24h=").append(asset.getPanelExposureCount24h() == null ? 0 : asset.getPanelExposureCount24h());
        reason.append(",panel_cooldown_mins=").append(asset.getDupExposureCooldownMinutes() == null ? 0 : asset.getDupExposureCooldownMinutes());
        reason.append(",panel_cooldown_active=").append(hasPanelExposureCooldown(asset, OffsetDateTime.now()));
        reason.append(",stale_penalty=").append(scale(stalePenalty));
        reason.append(",quality_penalty=").append(scale(qualityPenalty));
        if (asset.getSelectionReason() != null && !asset.getSelectionReason().isBlank()) {
            reason.append(",universe_reason=[").append(trimForMeta(asset.getSelectionReason(), 120)).append("]");
        }

        PanelSelectionMeta meta = new PanelSelectionMeta(
                asset.getUniverseLayer() == null ? null : asset.getUniverseLayer().name(),
                reason.toString(),
                false,
                scale(total.max(BigDecimal.ZERO).min(BigDecimal.ONE)),
                !isBlank(normalizedThemeFilter) && isPriorityTheme(normalizedThemeFilter),
                safeString(asset.getThemeCode(), null),
                strategyEval.strategyKey(),
                strategyEval.panelPurpose(),
                strategyEval.primaryMetricLabel(),
                scale(strategyEval.primaryMetricValue()),
                strategyEval.stateBadge(),
                strategyEval.stateReason(),
                strategyEval.recommendationState(),
                strategyEval.qualityDegraded(),
                strategyEval.sortBasis());
        return new PanelCandidate(signal, asset, total, meta);
    }

    private PanelStrategyEvaluation evaluatePanelStrategy(
            PanelType panelType,
            TradingSignalEntity signal,
            AssetUniverseEntity asset) {
        return switch (panelType) {
            case SCALP -> scalpStrategyService.evaluate(signal, asset);
            case SWING -> swingStrategyService.evaluate(signal, asset);
            case POSITION -> chartResponseStrategyService.evaluate(signal, asset);
            case DISCOVERY -> discoveryStrategyService.evaluate(signal, asset);
        };
    }

    private BigDecimal panelSignalScore(PanelType panelType, TradingSignalEntity signal) {
        return switch (panelType) {
            case SCALP -> clamp01(scale(signal.getScalpSignalScore()).add(BigDecimal.valueOf(0.5d)));
            case SWING -> clamp01(scale(signal.getSwingSignalScore()).add(BigDecimal.valueOf(0.5d)));
            case DISCOVERY -> clamp01(scale(signal.getDiscoveryScore()));
            case POSITION -> clamp01(scale(signal.getPositionManagementSignal()));
        };
    }

    private BigDecimal layerBonus(PanelType panelType, UniverseLayerType layer) {
        if (layer == null) {
            return BigDecimal.ZERO;
        }
        return switch (panelType) {
            case SCALP -> switch (layer) {
                case THEME_LEADER -> BigDecimal.valueOf(0.08d);
                case WATCHLIST -> BigDecimal.valueOf(0.06d);
                case CORE -> BigDecimal.valueOf(0.03d);
                case DISCOVERY -> BigDecimal.ZERO;
            };
            case SWING -> switch (layer) {
                case CORE -> BigDecimal.valueOf(0.08d);
                case WATCHLIST -> BigDecimal.valueOf(0.04d);
                case THEME_LEADER -> BigDecimal.valueOf(0.03d);
                case DISCOVERY -> BigDecimal.ZERO;
            };
            case DISCOVERY -> switch (layer) {
                case DISCOVERY -> BigDecimal.valueOf(0.10d);
                case THEME_LEADER -> BigDecimal.valueOf(0.04d);
                case WATCHLIST -> BigDecimal.valueOf(0.02d);
                case CORE -> BigDecimal.ZERO;
            };
            case POSITION -> BigDecimal.ZERO;
        };
    }

    private BigDecimal stalePenalty(AssetUniverseEntity asset) {
        if (asset == null) {
            return BigDecimal.valueOf(0.5d);
        }
        BigDecimal penalty = BigDecimal.ZERO;
        if (!Boolean.TRUE.equals(asset.getIsTradeEnabled())) {
            penalty = penalty.add(BigDecimal.valueOf(0.20d));
        }
        if (asset.getLastQuoteReceivedAt() == null) {
            penalty = penalty.add(BigDecimal.valueOf(0.10d));
        }
        if (asset.getLastSignalGeneratedAt() != null && asset.getDupExposureCooldownMinutes() != null && asset.getDupExposureCooldownMinutes() > 0) {
            OffsetDateTime cooldownUntil = asset.getLastSignalGeneratedAt().plusMinutes(asset.getDupExposureCooldownMinutes());
            if (OffsetDateTime.now().isBefore(cooldownUntil)) {
                penalty = penalty.add(BigDecimal.valueOf(0.08d));
            }
        }
        return penalty;
    }

    private TradingSignalViewDto toViewDto(TradingSignalEntity signal, AssetUniverseEntity asset, PanelSelectionMeta panelMeta) {
        String assetName = asset != null && asset.getAssetName() != null ? asset.getAssetName()
                : assetUniverseRepository.findById(signal.getAssetCode()).map(AssetUniverseEntity::getAssetName).orElse("-");
        MarketQuoteSnapshotEntity quote = marketQuoteSnapshotRepository
                .findTop1ByAssetCodeOrderBySnapshotUtcDesc(signal.getAssetCode())
                .orElse(null);
        QuoteViewState quoteView = resolveQuoteViewState(signal.getAssetCode(), quote);
        Map<String, Object> scalpBreakdown = parseJsonMap(signal.getProbabilityReasonBreakdownJson());
        Map<String, Object> reasonRoot = parseJsonMap(signal.getReasonJson());
        Map<String, Object> strategyEvidence = buildStrategyEvidence(signal, asset, panelMeta, reasonRoot, scalpBreakdown);
        List<String> strategyEvidenceTags = strategyEvidenceTags(strategyEvidence);
        return TradingSignalViewDto.builder()
                .signalId(signal.getId())
                .assetCode(signal.getAssetCode())
                .assetName(assetName)
                .country(signal.getCountry())
                .theme(signal.getTheme())
                .action(signal.getAction())
                .marketRegime(signal.getMarketRegime())
                .goodNewsProbability(scale(signal.getGoodNewsProbability()))
                .badNewsProbability(scale(signal.getBadNewsProbability()))
                .newsConfidence(scale(signal.getNewsConfidence()))
                .analysisState(textField(scalpBreakdown, "analysis_state"))
                .dataState(textField(scalpBreakdown, "data_state"))
                .probabilityReasonBreakdownJson(signal.getProbabilityReasonBreakdownJson())
                .chartConfidence(scale(signal.getChartConfidence()))
                .combinedConfidence(scale(signal.getCombinedConfidence()))
                .weeklyContextScore(scale(signal.getWeeklyContextScore()))
                .scalpSignalScore(scale(signal.getScalpSignalScore()))
                .swingSignalScore(scale(signal.getSwingSignalScore()))
                .positionManagementSignal(scale(signal.getPositionManagementSignal()))
                .discoveryScore(scale(signal.getDiscoveryScore()))
                .pressureReasonJson(signal.getPressureReasonJson())
                .riskChecks(parseRiskChecks(signal.getRiskChecks()))
                .blockedReason(signal.getBlockedReason())
                .reasonJson(signal.getReasonJson())
                .universeLayer(panelMeta == null ? null : panelMeta.universeLayer())
                .selectionReason(panelMeta == null ? null : panelMeta.selectionReason())
                .dedupApplied(panelMeta == null ? null : panelMeta.dedupApplied())
                .diversityScore(panelMeta == null ? null : panelMeta.diversityScore())
                .coreThemeFilterApplied(panelMeta == null ? null : panelMeta.coreThemeFilterApplied())
                .themeCode(panelMeta == null ? null : panelMeta.themeCode())
                .countryCode(asset == null ? null : safeString(asset.getCountryCode(), asset.getCountry()))
                .strategyScope(asset == null ? null : safeString(asset.getStrategyScope(), "ALL"))
                .panelExposureCount24h(asset == null ? null : effectivePanelExposureCount(asset, OffsetDateTime.now()))
                .dupExposureCooldownMinutes(asset == null ? null : (asset.getDupExposureCooldownMinutes() == null ? 0 : asset.getDupExposureCooldownMinutes()))
                .lastPanelExposedAt(asset == null ? null : asset.getLastPanelExposedAt())
                .panelCooldownActive(asset == null ? null : hasPanelExposureCooldown(asset, OffsetDateTime.now()))
                .strategyKey(panelMeta == null ? null : panelMeta.strategyKey())
                .panelPurpose(panelMeta == null ? null : panelMeta.panelPurpose())
                .primaryMetricLabel(panelMeta == null ? null : panelMeta.primaryMetricLabel())
                .primaryMetricValue(panelMeta == null ? null : panelMeta.primaryMetricValue())
                .stateBadge(panelMeta == null ? null : panelMeta.stateBadge())
                .stateReason(panelMeta == null ? null : panelMeta.stateReason())
                .strategyEvidenceSummary(strategyEvidenceSummary(strategyEvidence))
                .strategyEvidenceTags(strategyEvidenceTags)
                .strategyEvidence(strategyEvidence)
                .riskGuidance(buildRiskGuidance(signal))
                .recommendationState(panelMeta == null ? null : panelMeta.recommendationState())
                .qualityDegraded(panelMeta == null ? null : panelMeta.qualityDegraded())
                .sortBasis(panelMeta == null ? null : panelMeta.sortBasis())
                .lastPrice(quoteView.lastPrice())
                .changePct(quoteView.changePct())
                .volume(quoteView.volume())
                .quoteTimeUtc(quoteView.quoteTimeUtc())
                .quoteAgeSeconds(quoteView.quoteAgeSeconds())
                .quoteProvider(quoteView.quoteProvider())
                .generatedAt(signal.getGeneratedAt())
                .build();
    }

    private QuoteViewState resolveQuoteViewState(String assetCode, MarketQuoteSnapshotEntity quote) {
        OffsetDateTime quoteTime = quote == null
                ? null
                : (quote.getQuoteTimeUtc() != null ? quote.getQuoteTimeUtc() : quote.getSnapshotUtc());
        BigDecimal lastPrice = quote == null ? null : scale(quote.getLastPrice());
        BigDecimal changePct = quote == null ? null : scale(quote.getChangePct());
        BigDecimal volume = quote == null ? null : scale(quote.getVolume());
        String provider = quote == null ? null : quote.getProviderName();

        List<MarketPriceBarEntity> dailyBars = marketPriceBarRepository
                .findTop2ByAssetCodeAndTimeframeOrderByBarTimeDesc(assetCode, "D1");
        if (dailyBars == null || dailyBars.isEmpty()) {
            dailyBars = marketPriceBarRepository.findTop2ByAssetCodeAndTimeframeOrderByBarTimeDesc(assetCode, "d1");
        }
        if (dailyBars == null) {
            dailyBars = List.of();
        }

        MarketPriceBarEntity latestBar = dailyBars.isEmpty() ? null : dailyBars.get(0);
        if ((lastPrice == null || lastPrice.compareTo(BigDecimal.ZERO) <= 0)
                && latestBar != null && latestBar.getClosePrice() != null) {
            lastPrice = scale(latestBar.getClosePrice());
        }
        if ((volume == null || volume.compareTo(BigDecimal.ZERO) <= 0)
                && latestBar != null && latestBar.getVolume() != null) {
            volume = scale(latestBar.getVolume());
        }
        if (changePct == null || changePct.compareTo(BigDecimal.ZERO) == 0) {
            changePct = fallbackChangePct(dailyBars);
        }
        if (quoteTime == null && latestBar != null) {
            quoteTime = latestBar.getBarTimeUtc() != null ? latestBar.getBarTimeUtc() : latestBar.getBarTime();
        }
        if (isBlank(provider) && latestBar != null) {
            provider = latestBar.getProviderName();
        }
        Long quoteAgeSeconds = quoteTime == null ? null : Math.max(0L, ChronoUnit.SECONDS.between(quoteTime, OffsetDateTime.now()));
        return new QuoteViewState(lastPrice, changePct, volume, quoteTime, quoteAgeSeconds, provider);
    }

    private BigDecimal fallbackChangePct(List<MarketPriceBarEntity> dailyBars) {
        if (dailyBars == null || dailyBars.size() < 2) {
            return null;
        }
        BigDecimal latest = dailyBars.get(0).getClosePrice();
        BigDecimal previous = dailyBars.get(1).getClosePrice();
        if (latest == null || previous == null || previous.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return latest.subtract(previous)
                .divide(previous, MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> buildStrategyEvidence(
            TradingSignalEntity signal,
            AssetUniverseEntity asset,
            PanelSelectionMeta panelMeta,
            Map<String, Object> reasonRoot,
            Map<String, Object> scalpBreakdown) {
        Map<String, Object> root = reasonRoot == null ? Map.of() : reasonRoot;
        Map<String, Object> scalp = nestedMapField(root, "scalp");
        Map<String, Object> chart = nestedMapField(root, "chart_position");
        Map<String, Object> trend = nestedMapField(root, "market_trend");
        Map<String, Object> discovery = nestedMapField(root, "discovery");
        Map<String, Object> weekly = nestedMapField(root, "weekly_context");
        Map<String, Object> pressure = nestedMapField(root, "pressure");
        Map<String, Object> risk = nestedMapField(root, "risk");
        Map<String, Object> scalpProbability = nestedMapField(scalp, "probability_reason_breakdown");
        if (scalpBreakdown != null && !scalpBreakdown.isEmpty()) {
            scalpProbability = scalpBreakdown;
        }
        String strategyKey = panelMeta != null && !isBlank(panelMeta.strategyKey())
                ? panelMeta.strategyKey()
                : inferStrategyKey(signal);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("strategy_key", strategyKey);
        evidence.put("signal_window", signal == null ? null : signal.getSignalWindow());
        evidence.put("action", signal == null || signal.getAction() == null ? null : signal.getAction().name());
        evidence.put("panel_purpose", panelMeta == null ? null : panelMeta.panelPurpose());
        evidence.put("combined_confidence", signal == null ? null : scale(signal.getCombinedConfidence()));
        evidence.put("market_regime", signal == null || signal.getMarketRegime() == null ? null : signal.getMarketRegime().name());
        evidence.put("blocked_reason", signal == null ? null : signal.getBlockedReason());
        evidence.put("analysis_state", textField(scalpProbability, "analysis_state"));
        evidence.put("data_state", textField(scalpProbability, "data_state"));
        evidence.put("fusion_inputs", buildFusionInputFlags(strategyKey));

        List<String> tags = new ArrayList<>();
        String summary;
        switch (strategyKey) {
            case "SCALP" -> {
                Object matched = mapField(scalp, "matched_news_count", mapField(scalpProbability, "matched_news_count", 0));
                evidence.put("primary_metric_label", "단타점수");
                evidence.put("primary_metric_value", scale(signal == null ? null : signal.getScalpSignalScore()));
                evidence.put("good_news_probability", scale(signal == null ? null : signal.getGoodNewsProbability()));
                evidence.put("bad_news_probability", scale(signal == null ? null : signal.getBadNewsProbability()));
                evidence.put("news_confidence", scale(signal == null ? null : signal.getNewsConfidence()));
                evidence.put("matched_news_count", matched);
                evidence.put("analysis_state", textField(scalpProbability, "analysis_state"));
                evidence.put("data_state", textField(scalpProbability, "data_state"));
                if (!isBlank(textField(scalpProbability, "analysis_state"))) {
                    tags.add(textField(scalpProbability, "analysis_state"));
                }
                if (!isBlank(textField(scalpProbability, "data_state"))) {
                    tags.add(textField(scalpProbability, "data_state"));
                }
                tags.add("NEWS");
                tags.add("CHART_FUSION");
                summary = "뉴스 " + String.valueOf(matched == null ? 0 : matched)
                        + "건 · 호/악 "
                        + scale(signal == null ? null : signal.getGoodNewsProbability()) + "/"
                        + scale(signal == null ? null : signal.getBadNewsProbability())
                        + " · 단타점수 " + scale(signal == null ? null : signal.getScalpSignalScore());
            }
            case "SWING" -> {
                evidence.put("primary_metric_label", "스윙점수");
                evidence.put("primary_metric_value", scale(signal == null ? null : signal.getSwingSignalScore()));
                evidence.put("swing_signal_score", scale(signal == null ? null : signal.getSwingSignalScore()));
                evidence.put("weekly_context_score", scale(signal == null ? null : signal.getWeeklyContextScore()));
                evidence.put("theme_strength_score", decimalField(trend, "theme_strength_score"));
                evidence.put("market_regime", textField(trend, "market_regime"));
                tags.add("NEWS");
                tags.add("CHART");
                tags.add("WEEKLY");
                if (!isBlank(textField(trend, "market_regime"))) {
                    tags.add("REGIME:" + textField(trend, "market_regime"));
                }
                summary = "주간컨텍스트 " + scale(signal == null ? null : signal.getWeeklyContextScore())
                        + " · 스윙점수 " + scale(signal == null ? null : signal.getSwingSignalScore())
                        + " · 레짐 " + safeString(textField(trend, "market_regime"), "MIXED");
            }
            case "CHART_RESPONSE" -> {
                boolean volumeSame = boolField(pressure, "volume_regime_same")
                        || Boolean.TRUE.equals(signal == null ? null : signal.getVolumeRegimeSame());
                boolean sellDetected = boolField(pressure, "sell_pressure_detected")
                        || Boolean.TRUE.equals(signal == null ? null : signal.getSellPressureDetected());
                boolean buyDetected = boolField(pressure, "buy_pressure_detected")
                        || Boolean.TRUE.equals(signal == null ? null : signal.getBuyPressureDetected());
                boolean sellNegative = boolField(pressure, "sell_pressure_is_negative")
                        || Boolean.TRUE.equals(signal == null ? null : signal.getSellPressureIsNegative());
                boolean buyPositive = boolField(pressure, "buy_pressure_is_positive")
                        || Boolean.TRUE.equals(signal == null ? null : signal.getBuyPressureIsPositive());
                boolean neutralized = volumeSame && (sellDetected || buyDetected) && !sellNegative && !buyPositive;

                evidence.put("primary_metric_label", "포지션관리");
                evidence.put("primary_metric_value", scale(signal == null ? null : signal.getPositionManagementSignal()));
                evidence.put("position_management_signal", scale(signal == null ? null : signal.getPositionManagementSignal()));
                evidence.put("chart_confidence", scale(signal == null ? null : signal.getChartConfidence()));
                evidence.put("avg_down_allowed", signal == null ? null : signal.getAvgDownAllowed());
                evidence.put("avg_down_stage", signal == null ? null : signal.getAvgDownStage());
                evidence.put("avg_down_next_buy_ratio", scale(signal == null ? null : signal.getAvgDownNextBuyRatio()));
                evidence.put("avg_down_reason", signal == null ? null : signal.getAvgDownReason());
                evidence.put("pressure", Map.of(
                        "sell_pressure_detected", sellDetected,
                        "sell_pressure_is_negative", sellNegative,
                        "buy_pressure_detected", buyDetected,
                        "buy_pressure_is_positive", buyPositive,
                        "volume_regime_same", volumeSame,
                        "neutralized_by_volume_same", neutralized));
                tags.add("CHART");
                tags.add("PRESSURE");
                tags.add("NEWS_GUARD");
                if (neutralized) {
                    tags.add("VOLUME_SAME_NEUTRALIZED");
                }
                if (Boolean.TRUE.equals(signal == null ? null : signal.getAvgDownAllowed())) {
                    tags.add("AVG_DOWN_ALLOWED");
                }
                summary = "포지션관리 " + scale(signal == null ? null : signal.getPositionManagementSignal())
                        + " · 차트신뢰 " + scale(signal == null ? null : signal.getChartConfidence())
                        + " · 평단가단계 " + (signal == null || signal.getAvgDownStage() == null ? 0 : signal.getAvgDownStage())
                        + (neutralized ? " · 압력중립(거래량 동일)" : "");
            }
            case "DISCOVERY" -> {
                Object rank = mapField(discovery, "candidate_rank", null);
                evidence.put("primary_metric_label", "발굴점수");
                evidence.put("primary_metric_value", scale(signal == null ? null : signal.getDiscoveryScore()));
                evidence.put("discovery_score", scale(signal == null ? null : signal.getDiscoveryScore()));
                evidence.put("candidate_rank", rank);
                evidence.put("candidate_reason_json", textField(discovery, "candidate_reason_json"));
                tags.add("THEME");
                tags.add("LONG_TERM");
                tags.add("NEWS_CHART_FUSION");
                summary = "발굴점수 " + scale(signal == null ? null : signal.getDiscoveryScore())
                        + (rank == null ? "" : " · 후보순위 " + rank);
            }
            default -> {
                evidence.put("primary_metric_label", panelMeta == null ? "점수" : panelMeta.primaryMetricLabel());
                evidence.put("primary_metric_value", panelMeta == null ? BigDecimal.ZERO : panelMeta.primaryMetricValue());
                tags.add("FUSION");
                summary = "결합신뢰 " + scale(signal == null ? null : signal.getCombinedConfidence());
            }
        }
        if (signal != null && signal.getBlockedReason() != null && !signal.getBlockedReason().isBlank()) {
            tags.add("BLOCKED");
        }
        if (asset != null && !Boolean.TRUE.equals(asset.getIsTradeEnabled())) {
            tags.add("TRADE_DISABLED");
        }
        if (panelMeta != null && !isBlank(panelMeta.stateReason())) {
            summary = summary + " · " + panelMeta.stateReason();
        }
        evidence.put("tags", tags.stream().distinct().toList());
        evidence.put("summary", summary);
        return evidence;
    }

    private Map<String, Object> buildFusionInputFlags(String strategyKey) {
        String key = strategyKey == null ? "UNKNOWN" : strategyKey.toUpperCase(Locale.ROOT);
        return switch (key) {
            case "SCALP" -> Map.of(
                    "uses_news", true,
                    "uses_chart", true,
                    "uses_pressure", true,
                    "uses_weekly_context", true,
                    "mode", "news_first_with_chart_guard");
            case "SWING" -> Map.of(
                    "uses_news", true,
                    "uses_chart", true,
                    "uses_pressure", false,
                    "uses_weekly_context", true,
                    "mode", "weekly_context_fusion");
            case "CHART_RESPONSE" -> Map.of(
                    "uses_news", true,
                    "uses_chart", true,
                    "uses_pressure", true,
                    "uses_weekly_context", true,
                    "mode", "chart_response_with_news_guard");
            case "DISCOVERY" -> Map.of(
                    "uses_news", true,
                    "uses_chart", true,
                    "uses_pressure", false,
                    "uses_weekly_context", true,
                    "mode", "long_term_discovery_fusion");
            default -> Map.of(
                    "uses_news", true,
                    "uses_chart", true,
                    "uses_pressure", true,
                    "uses_weekly_context", true,
                    "mode", "fusion");
        };
    }

    private String strategyEvidenceSummary(Map<String, Object> strategyEvidence) {
        String summary = textField(strategyEvidence, "summary");
        return summary == null ? "" : summary;
    }

    private List<String> strategyEvidenceTags(Map<String, Object> strategyEvidence) {
        return stringListField(strategyEvidence, "tags");
    }

    private Map<String, Object> buildRiskGuidance(TradingSignalEntity signal) {
        Map<String, Object> guide = new LinkedHashMap<>();
        BigDecimal capitalValue = riskPolicyService.effectiveCapitalTotal();
        BigDecimal capital = capitalValue == null ? BigDecimal.ZERO : capitalValue;
        BigDecimal nextBuyRatio = signal == null || signal.getAvgDownNextBuyRatio() == null
                ? BigDecimal.ZERO
                : signal.getAvgDownNextBuyRatio();
        BigDecimal nextBuyAmount = capital.multiply(nextBuyRatio).setScale(2, RoundingMode.HALF_UP);
        guide.put("reference_only", true);
        guide.put("capital_total", capital);
        guide.put("buy_split_ratios", riskPolicyService.effectiveBuySplitRules());
        guide.put("sell_split_ratios", riskPolicyService.effectiveSellSplitRules());
        guide.put("take_profit_pct", riskPolicyService.effectiveTakeProfitPct());
        guide.put("stop_loss_pct", riskPolicyService.effectiveStopLossPct());
        guide.put("reanalysis_lock_minutes", riskPolicyService.effectiveReanalysisLockMinutes());
        guide.put("buy_lock_active", signal != null
                && (signal.getAction() == SignalActionType.BUY_LOCK || Boolean.TRUE.equals(signal.getReanalysisLockRequired())));
        guide.put("reanalysis_lock_required", signal == null ? false : Boolean.TRUE.equals(signal.getReanalysisLockRequired()));
        guide.put("reanalysis_lock_until", signal == null ? null : signal.getReanalysisLockUntil());
        guide.put("avg_down_allowed", signal == null ? null : signal.getAvgDownAllowed());
        guide.put("avg_down_stage", signal == null ? null : signal.getAvgDownStage());
        guide.put("avg_down_reason", signal == null ? null : signal.getAvgDownReason());
        guide.put("avg_down_next_buy_ratio", scale(nextBuyRatio));
        guide.put("avg_down_next_buy_amount_reference", nextBuyAmount);
        return guide;
    }

    private List<Map<String, Object>> buildStrategyComparison(String assetCode, AssetUniverseEntity asset) {
        if (assetCode == null || assetCode.isBlank()) {
            return List.of();
        }
        List<TradingSignalEntity> recent = tradingSignalRepository.findTop200ByAssetCodeOrderByGeneratedAtDesc(assetCode);
        Map<String, TradingSignalEntity> byWindow = new LinkedHashMap<>();
        for (TradingSignalEntity row : recent) {
            if (row == null || row.getSignalWindow() == null) {
                continue;
            }
            if (!byWindow.containsKey(row.getSignalWindow()) && isKnownStrategyWindow(row.getSignalWindow())) {
                byWindow.put(row.getSignalWindow(), row);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(toStrategyComparisonRow("SCALP", "1h", byWindow.get("1h"), asset));
        rows.add(toStrategyComparisonRow("SWING", "1w", byWindow.get("1w"), asset));
        rows.add(toStrategyComparisonRow("CHART_RESPONSE", "1m", byWindow.get("1m"), asset));
        rows.add(toStrategyComparisonRow("DISCOVERY", "6m", byWindow.get("6m"), asset));
        return rows;
    }

    private Map<String, Object> toStrategyComparisonRow(
            String strategyKey,
            String signalWindow,
            TradingSignalEntity signal,
            AssetUniverseEntity asset) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("strategy_key", strategyKey);
        row.put("signal_window", signalWindow);
        if (signal == null) {
            row.put("present", false);
            row.put("summary", "최근 전략 결과 없음");
            row.put("tags", List.of("MISSING_SIGNAL"));
            return row;
        }
        PanelStrategyEvaluation eval = evaluatePanelStrategy(signal, asset);
        Map<String, Object> scalpBreakdown = parseJsonMap(signal.getProbabilityReasonBreakdownJson());
        Map<String, Object> reasonRoot = parseJsonMap(signal.getReasonJson());
        Map<String, Object> evidence = buildStrategyEvidence(signal, asset, null, reasonRoot, scalpBreakdown);
        row.put("present", true);
        row.put("signal_id", signal.getId());
        row.put("action", signal.getAction() == null ? null : signal.getAction().name());
        row.put("generated_at", signal.getGeneratedAt());
        row.put("combined_confidence", scale(signal.getCombinedConfidence()));
        row.put("primary_metric_label", eval == null ? textField(evidence, "primary_metric_label") : eval.primaryMetricLabel());
        row.put("primary_metric_value", eval == null ? mapField(evidence, "primary_metric_value", null) : eval.primaryMetricValue());
        row.put("state_badge", eval == null ? null : eval.stateBadge());
        row.put("state_reason", eval == null ? null : eval.stateReason());
        row.put("blocked_reason", signal.getBlockedReason());
        row.put("analysis_state", textField(scalpBreakdown, "analysis_state"));
        row.put("data_state", textField(scalpBreakdown, "data_state"));
        row.put("summary", strategyEvidenceSummary(evidence));
        row.put("tags", strategyEvidenceTags(evidence));
        row.put("evidence", evidence);
        row.put("risk_guidance", buildRiskGuidance(signal));
        return row;
    }

    private PanelStrategyEvaluation evaluatePanelStrategy(TradingSignalEntity signal, AssetUniverseEntity asset) {
        if (signal == null || signal.getSignalWindow() == null) {
            return null;
        }
        return switch (signal.getSignalWindow()) {
            case "1h" -> scalpStrategyService.evaluate(signal, asset);
            case "1w" -> swingStrategyService.evaluate(signal, asset);
            case "1m" -> chartResponseStrategyService.evaluate(signal, asset);
            case "6m" -> discoveryStrategyService.evaluate(signal, asset);
            default -> null;
        };
    }

    private boolean isKnownStrategyWindow(String window) {
        return Objects.equals("1h", window)
                || Objects.equals("1w", window)
                || Objects.equals("1m", window)
                || Objects.equals("6m", window);
    }

    private String inferStrategyKey(TradingSignalEntity signal) {
        if (signal == null || signal.getSignalWindow() == null) {
            return "UNKNOWN";
        }
        return switch (signal.getSignalWindow()) {
            case "1h" -> "SCALP";
            case "1w" -> "SWING";
            case "1m" -> "CHART_RESPONSE";
            case "6m" -> "DISCOVERY";
            default -> "OTHER";
        };
    }

    private Map<String, Object> nestedMapField(Map<String, Object> map, String key) {
        Object raw = mapField(map, key, Map.of());
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        if (raw instanceof String rawText) {
            return parseJsonMap(rawText);
        }
        return new LinkedHashMap<>();
    }

    private BigDecimal defaultRequestRatio() {
        List<Integer> rules = riskPolicyService.effectiveBuySplitRules();
        if (rules.isEmpty()) {
            return BigDecimal.valueOf(0.2d);
        }
        return BigDecimal.valueOf(rules.get(0)).divide(BigDecimal.valueOf(100d), 4, RoundingMode.HALF_UP);
    }

    private String resolveSignalWindow(StrategyRunType runType) {
        return switch (runType) {
            case SCALP -> "1h";
            case SWING -> "1w";
            case POSITION -> "1m";
            case DISCOVERY -> "6m";
            default -> "1d";
        };
    }

    private boolean matchesPanelStrategyScope(PanelType panelType, AssetUniverseEntity asset) {
        if (asset == null) {
            return false;
        }
        String scope = safeString(asset.getStrategyScope(), "ALL").toUpperCase(Locale.ROOT);
        if ("ALL".equals(scope) || scope.isBlank()) {
            return true;
        }
        return switch (panelType) {
            case SCALP -> Set.of("SCALP", "SCALP_SWING", "ALL").contains(scope);
            case SWING -> Set.of("SWING", "SCALP_SWING", "ALL").contains(scope);
            case DISCOVERY -> Set.of("DISCOVERY", "ALL").contains(scope);
            case POSITION -> true;
        };
    }

    private int effectivePanelExposureCount(AssetUniverseEntity asset, OffsetDateTime now) {
        if (asset == null) {
            return 0;
        }
        int current = asset.getPanelExposureCount24h() == null ? 0 : Math.max(0, asset.getPanelExposureCount24h());
        OffsetDateTime last = asset.getLastPanelExposedAt();
        if (last != null && last.isBefore(now.minusHours(24))) {
            return 0;
        }
        return current;
    }

    private boolean hasPanelExposureCooldown(AssetUniverseEntity asset, OffsetDateTime now) {
        if (asset == null || asset.getLastPanelExposedAt() == null) {
            return false;
        }
        int cooldownMinutes = asset.getDupExposureCooldownMinutes() == null ? 0 : Math.max(0, asset.getDupExposureCooldownMinutes());
        if (cooldownMinutes <= 0) {
            return false;
        }
        return asset.getLastPanelExposedAt().isAfter(now.minusMinutes(cooldownMinutes));
    }

    private boolean isPanelExposureLimitExceeded(AssetUniverseEntity asset, OffsetDateTime now) {
        if (asset == null) {
            return false;
        }
        int limit = Math.max(1, panelMaxExposurePer24h);
        int count = effectivePanelExposureCount(asset, now);
        return count >= limit;
    }

    private void recordPanelExposureSafely(
            List<PanelCandidate> selectedCandidates,
            OffsetDateTime selectedAt,
            PanelType panelType,
            String country,
            String theme) {
        if (!panelExposureWriteEnabled || selectedCandidates == null || selectedCandidates.isEmpty()) {
            return;
        }
        try {
            recordPanelExposure(selectedCandidates, selectedAt);
        } catch (RuntimeException e) {
            log.warn(
                    "panel exposure update skipped panel={} country={} theme={} reason={} trace_id={}",
                    panelType == null ? "UNKNOWN" : panelType.name(),
                    safeString(country, "ALL"),
                    safeString(theme, "ALL"),
                    e.getMessage(),
                    traceId());
        }
    }

    private void recordPanelExposure(List<PanelCandidate> selectedCandidates, OffsetDateTime selectedAt) {
        if (selectedCandidates == null || selectedCandidates.isEmpty()) {
            return;
        }
        List<AssetUniverseEntity> touched = selectedCandidates.stream()
                .map(PanelCandidate::asset)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (touched.isEmpty()) {
            return;
        }
        for (AssetUniverseEntity asset : touched) {
            int nextCount = effectivePanelExposureCount(asset, selectedAt) + 1;
            asset.setPanelExposureCount24h(nextCount);
            asset.setLastPanelExposedAt(selectedAt);
        }
        assetUniverseRepository.saveAll(touched);
    }

    private void logPanelSelectionDuplicatesIfNeeded(
            PanelType panelType,
            String country,
            String normalizedTheme,
            List<PanelCandidate> selectedCandidates,
            int scopeFilteredCount,
            int cooldownFilteredCount,
            int exposureCapFilteredCount,
            int themeFilteredCount,
            boolean duplicateSignalRowsFound) {
        if (selectedCandidates == null || selectedCandidates.isEmpty()) {
            return;
        }
        Map<String, Long> familyCounts = selectedCandidates.stream()
                .map(candidate -> tickerFamily(candidate.signal().getAssetCode()))
                .collect(java.util.stream.Collectors.groupingBy(v -> v, Collectors.counting()));
        Map<String, Long> themeCounts = selectedCandidates.stream()
                .map(candidate -> safeString(candidate.asset() == null ? null : candidate.asset().getThemeCode(), "N/A"))
                .collect(java.util.stream.Collectors.groupingBy(v -> v, Collectors.counting()));
        long maxFamily = familyCounts.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        long maxTheme = themeCounts.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        boolean warning = duplicateSignalRowsFound
                || scopeFilteredCount > 0
                || cooldownFilteredCount > 0
                || exposureCapFilteredCount > 0
                || maxFamily > Math.max(1, panelMaxSameFamily)
                || (normalizedTheme == null || normalizedTheme.isBlank())
                        && maxTheme > Math.max(1, panelMaxSameTheme);
        if (!warning) {
            return;
        }
        log.warn(
                "panel-selection-dedup panel={} country={} theme={} selected={} duplicate_signal_rows={} scope_filtered={} cooldown_filtered={} exposure_cap_filtered={} theme_filtered={} max_family={} max_theme={} trace_id={}",
                panelType.name(),
                country,
                safeString(normalizedTheme, "ALL"),
                selectedCandidates.size(),
                duplicateSignalRowsFound,
                scopeFilteredCount,
                cooldownFilteredCount,
                exposureCapFilteredCount,
                themeFilteredCount,
                maxFamily,
                maxTheme,
                traceId());
    }

    private void logPanelSelectionWarning(
            PanelType panelType,
            String country,
            String normalizedTheme,
            String reason,
            int latestRows,
            int candidateRows,
            int selectedRows,
            int scopeFilteredCount,
            int cooldownFilteredCount,
            int exposureCapFilteredCount,
            int themeFilteredCount,
            boolean duplicateSignalRowsFound) {
        log.warn(
                "panel-selection-warning panel={} country={} theme={} reason={} latest_rows={} candidates={} selected={} duplicate_signal_rows={} scope_filtered={} cooldown_filtered={} exposure_cap_filtered={} theme_filtered={} trace_id={}",
                panelType.name(),
                country,
                safeString(normalizedTheme, "ALL"),
                reason,
                latestRows,
                candidateRows,
                selectedRows,
                duplicateSignalRowsFound,
                scopeFilteredCount,
                cooldownFilteredCount,
                exposureCapFilteredCount,
                themeFilteredCount,
                traceId());
    }

    private boolean matchesThemeFilter(String theme, TradingSignalEntity signal, AssetUniverseEntity asset) {
        if (theme == null || theme.isBlank()) {
            return true;
        }
        String normalizedTheme = normalizeThemeCode(theme);
        if (normalizedTheme == null || normalizedTheme.isBlank()) {
            return true;
        }
        if ("ALL".equalsIgnoreCase(normalizedTheme) || "TOTAL".equalsIgnoreCase(normalizedTheme)) {
            return true;
        }
        if (signal != null && Objects.equals(normalizedTheme, normalizeThemeCode(signal.getTheme()))) {
            return true;
        }
        if (asset != null && Objects.equals(normalizedTheme, normalizeThemeCode(asset.getTheme()))) {
            return true;
        }
        return asset != null && Objects.equals(normalizedTheme, normalizeThemeCode(asset.getThemeCode()));
    }

    private String toJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> parseRiskChecks(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson, new TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of(rawJson);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = objectMapper.readValue(rawJson, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return result;
            }
            if (parsed instanceof String nestedText && !nestedText.isBlank()) {
                Object nestedParsed = objectMapper.readValue(nestedText, Object.class);
                if (nestedParsed instanceof Map<?, ?> nestedMap) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : nestedMap.entrySet()) {
                        result.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    return result;
                }
            }
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<>();
    }

    private Object mapField(Map<String, Object> map, String key, Object fallback) {
        if (map == null || key == null) {
            return fallback;
        }
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringListField(Map<String, Object> map, String key) {
        Object value = mapField(map, key, List.of());
        if (value instanceof List<?> rows) {
            return rows.stream().map(v -> v == null ? "" : String.valueOf(v)).filter(v -> !v.isBlank()).toList();
        }
        return List.of();
    }

    private String textField(Map<String, Object> map, String key) {
        Object value = mapField(map, key, null);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private String toJsonValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal firstNonNullBigDecimal(BigDecimal... values) {
        if (values == null) {
            return null;
        }
        for (BigDecimal value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal decimalField(Map<String, Object> map, String key) {
        Object value = mapField(map, key, null);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean boolField(Map<String, Object> map, String key) {
        Object value = mapField(map, key, null);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private String resolveSignalExplainText(
            SignalActionType action,
            Map<String, Object> scalpBreakdown,
            RiskDecision riskDecision,
            TimeAlignmentValidationResult alignment) {
        String dataState = textField(scalpBreakdown, "data_state");
        String baseExplain = textField(scalpBreakdown, "explain_text");
        StringBuilder sb = new StringBuilder();
        sb.append("최종 액션=").append(action == null ? "WATCH" : action.name());
        if (riskDecision != null && riskDecision.blockedReason() != null && !riskDecision.blockedReason().isBlank()) {
            sb.append(" · 리스크 차단=").append(riskDecision.blockedReason());
        }
        if (alignment != null && alignment.futureDataDetected()) {
            sb.append(" · 시간정렬 경고=FUTURE_DATA_DETECTED");
        }
        if (dataState != null) {
            sb.append(" · 데이터상태=").append(dataState);
        }
        if (baseExplain != null && !baseExplain.isBlank()) {
            sb.append(" · ").append(baseExplain);
        }
        return sb.toString();
    }

    private List<String> buildNewsEvidence(TradingSignalEntity signal, Map<String, Object> scalpBreakdown) {
        List<String> rows = new ArrayList<>();
        rows.add("뉴스 매핑/이벤트/감성/신뢰도 기반 RULE_V1");
        String analysisState = textField(scalpBreakdown, "analysis_state");
        if (analysisState != null) {
            rows.add("분석 상태: " + analysisState);
        }
        String dataState = textField(scalpBreakdown, "data_state");
        if (dataState != null) {
            rows.add("데이터 상태: " + dataState);
        }
        Object mappedCount = mapField(scalpBreakdown, "mapped_news_count", null);
        Object eligibleCount = mapField(scalpBreakdown, "eligible_news_count", null);
        if (mappedCount != null || eligibleCount != null) {
            rows.add("매핑 뉴스 " + String.valueOf(mappedCount == null ? 0 : mappedCount)
                    + "건 / 유효 뉴스 " + String.valueOf(eligibleCount == null ? 0 : eligibleCount) + "건");
        }
        return rows;
    }

    private List<String> buildChartEvidence(TradingSignalEntity signal, Map<String, Object> reasonRoot) {
        List<String> rows = new ArrayList<>();
        rows.add("차트 RULESET_V1 + 뉴스/압력 가드 결합");
        Map<String, Object> root = reasonRoot == null ? Map.of() : reasonRoot;
        Map<String, Object> chart = nestedMapField(root, "chart_position");
        Map<String, Object> pressure = nestedMapField(root, "pressure");
        if (signal != null) {
            rows.add("차트신뢰 " + scale(signal.getChartConfidence()) + " / 포지션관리 " + scale(signal.getPositionManagementSignal()));
            if (Boolean.TRUE.equals(signal.getAvgDownAllowed())) {
                rows.add("평단가 대응 허용: 단계 " + (signal.getAvgDownStage() == null ? 0 : signal.getAvgDownStage())
                        + ", 다음비중 " + scale(signal.getAvgDownNextBuyRatio()));
            } else if (signal.getAvgDownReason() != null && !signal.getAvgDownReason().isBlank()) {
                rows.add("평단가 대응 보류: " + signal.getAvgDownReason());
            }
        }
        String riskWarning = textField(chart, "risk_warning");
        if (riskWarning != null) {
            rows.add("차트 리스크: " + riskWarning);
        }
        Map<String, Object> pressureReason = nestedMapField(pressure, "pressure_reason");
        boolean neutralized = boolField(pressureReason, "auto_classification_suppressed_by_volume_same")
                || (boolField(pressure, "volume_regime_same")
                        && (boolField(pressure, "sell_pressure_detected") || boolField(pressure, "buy_pressure_detected"))
                        && !boolField(pressure, "sell_pressure_is_negative")
                        && !boolField(pressure, "buy_pressure_is_positive"));
        if (neutralized) {
            rows.add("연속 매수/매도는 감지됐지만 거래량 동일 구간으로 자동 호/악재 단정 보류");
        }
        return rows;
    }

    private List<String> buildPriceEvidence(TradingSignalEntity signal, Map<String, Object> scalpBreakdown) {
        List<String> rows = new ArrayList<>();
        Map<String, Object> freshness = parseJsonMap(signal.getDataFreshnessJson());
        Object coverage = freshness.get("price_data_coverage_ratio");
        if (coverage != null) {
            rows.add("가격 데이터 커버리지: " + coverage);
        } else {
            rows.add("가격 반응 데이터 기반(가능한 범위)");
        }
        Object quoteAge = freshness.get("latest_quote_age_minutes");
        if (quoteAge != null) {
            rows.add("최신 시세 스냅샷 경과(분): " + quoteAge);
        }
        Object barAge = freshness.get("latest_bar_1m_age_minutes");
        if (barAge != null) {
            rows.add("최신 1분봉 경과(분): " + barAge);
        }
        return rows;
    }

    private List<String> buildVolumeEvidence(TradingSignalEntity signal, Map<String, Object> scalpBreakdown) {
        List<String> rows = new ArrayList<>();
        rows.add("거래량 변화율/가격반응 보조 피처 기반(가능한 범위)");
        Map<String, Object> freshness = parseJsonMap(signal.getDataFreshnessJson());
        Object coverage = freshness.get("price_data_coverage_ratio");
        if (coverage != null) {
            rows.add("거래량/가격 반응 데이터 커버리지: " + coverage);
        }
        Map<String, Object> dedup = parseJsonMap(signal.getDedupResultJson());
        Object dedupApplied = dedup.get("duplicate_article_penalty_applied");
        if (dedupApplied != null) {
            rows.add("중복기사 감점 적용: " + dedupApplied);
        }
        Map<String, Object> pressure = parseJsonMap(signal.getPressureReasonJson());
        if (!pressure.isEmpty()) {
            rows.add("압력 탐지: 매도연속=" + boolField(pressure, "sell_pressure_detected")
                    + ", 매수연속=" + boolField(pressure, "buy_pressure_detected")
                    + ", 거래량동일=" + boolField(pressure, "volume_regime_same"));
        }
        return rows;
    }

    private List<String> buildRiskEvidence(TradingSignalEntity signal, List<String> riskChecks) {
        List<String> rows = new ArrayList<>();
        rows.add("리스크 정책 + 시간정렬 검증 기반");
        if (signal.getBlockedReason() != null && !signal.getBlockedReason().isBlank()) {
            rows.add("차단 사유: " + signal.getBlockedReason());
        }
        if (riskChecks != null && !riskChecks.isEmpty()) {
            rows.add("리스크 체크 " + riskChecks.size() + "건");
        }
        if (signal != null && Boolean.TRUE.equals(signal.getReanalysisLockRequired())) {
            rows.add("BUY_LOCK/재분석 잠금 상태 또는 잠금 필요 플래그 존재");
        }
        if (signal != null && signal.getReanalysisLockUntil() != null) {
            rows.add("재분석 잠금 해제 예정: " + signal.getReanalysisLockUntil());
        }
        if (signal != null && signal.getAvgDownReason() != null && !signal.getAvgDownReason().isBlank()) {
            rows.add("평단가 정책 판정: " + signal.getAvgDownReason());
        }
        return rows;
    }

    private String buildDecisionWhy(
            TradingSignalEntity signal,
            String explainText,
            List<String> riskChecks,
            List<String> missingRequirements) {
        String action = signal.getAction() == null ? "WATCH" : signal.getAction().name();
        StringBuilder sb = new StringBuilder();
        sb.append(action).append(" 판단 근거: ");
        if (explainText != null && !explainText.isBlank()) {
            sb.append(explainText);
        } else {
            sb.append("결합 신뢰도=").append(scale(signal.getCombinedConfidence()));
        }
        if (missingRequirements != null && !missingRequirements.isEmpty()) {
            sb.append(" · 부족요건=").append(missingRequirements.get(0));
        }
        if (riskChecks != null && !riskChecks.isEmpty()) {
            sb.append(" · 리스크체크=").append(riskChecks.size()).append("건");
        }
        return sb.toString();
    }

    private BigDecimal scale(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal clamp01(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private String mergeBlockedReason(String riskBlockedReason, TimeAlignmentValidationResult alignment) {
        String base = (riskBlockedReason == null || riskBlockedReason.isBlank()) ? null : riskBlockedReason.trim();
        if (!alignment.futureDataDetected()) {
            return base;
        }
        String alignmentReason = "FUTURE_DATA_BLOCKED";
        if (base == null) {
            return alignmentReason;
        }
        if (base.contains(alignmentReason)) {
            return base;
        }
        return base + "|" + alignmentReason;
    }

    private int signalPolicyWindowMinutes() {
        Integer value = signalPolicyProperties.getNewsPriceAlignmentWindowMinutes();
        return value == null ? 60 : Math.max(1, value);
    }

    private Object parseJsonObject(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception ignored) {
            return Map.of("raw", rawJson);
        }
    }

    private boolean isPriorityTheme(String themeCode) {
        String normalized = normalizeThemeCode(themeCode);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        if (priorityThemes == null || priorityThemes.isEmpty()) {
            return Set.of("RESOURCE", "DEFENSE", "SPACE", "AI", "SEMICONDUCTOR", "ROBOTICS", "ENERGY").contains(normalized);
        }
        return priorityThemes.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(this::normalizeThemeCode)
                .filter(Objects::nonNull)
                .anyMatch(normalized::equals);
    }

    private String normalizeThemeCode(String rawTheme) {
        if (rawTheme == null || rawTheme.isBlank()) {
            return null;
        }
        String v = rawTheme.trim().toUpperCase(Locale.ROOT)
                .replace("&", " AND ")
                .replace("/", " ")
                .replace("-", " ")
                .replaceAll("[^A-Z0-9가-힣 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (v.isBlank()) {
            return null;
        }
        if (v.contains("AI") || v.contains("인공지능")) {
            return "AI";
        }
        if (v.contains("반도체") || v.contains("SEMICON") || v.contains("CHIP")) {
            return "SEMICONDUCTOR";
        }
        if (v.contains("방산") || v.contains("DEFENSE") || v.contains("군수")) {
            return "DEFENSE";
        }
        if (v.contains("우주") || v.contains("SPACE") || v.contains("AEROSPACE")) {
            return "SPACE";
        }
        if (v.contains("로봇") || v.contains("ROBOT")) {
            return "ROBOTICS";
        }
        if (v.contains("에너지") || v.contains("ENERGY") || v.contains("전력") || v.contains("OIL") || v.contains("GAS")) {
            return "ENERGY";
        }
        if (v.contains("금") || v.contains("은") || v.contains("구리") || v.contains("자원")
                || v.contains("RESOURCE") || v.contains("GOLD") || v.contains("SILVER") || v.contains("COPPER")) {
            return "RESOURCE";
        }
        return v.replace(" ", "_");
    }

    private String tickerFamily(String assetCode) {
        if (assetCode == null || assetCode.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = assetCode.toUpperCase(Locale.ROOT);
        int dot = normalized.indexOf('.');
        if (dot > 0) {
            normalized = normalized.substring(0, dot);
        }
        return normalized.replaceAll("[0-9]", "");
    }

    private String safeString(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimForMeta(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("[\\r\\n]+", " ").trim();
        int safeLen = Math.max(1, maxLen);
        return normalized.length() > safeLen ? normalized.substring(0, safeLen) : normalized;
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return trace == null ? "" : trace;
    }

    private enum PanelType {
        SCALP,
        SWING,
        POSITION,
        DISCOVERY
    }

    private record QuoteViewState(
            BigDecimal lastPrice,
            BigDecimal changePct,
            BigDecimal volume,
            OffsetDateTime quoteTimeUtc,
            Long quoteAgeSeconds,
            String quoteProvider) {
    }

    private record PanelCandidate(
            TradingSignalEntity signal,
            AssetUniverseEntity asset,
            BigDecimal panelScore,
            PanelSelectionMeta meta) {
    }

    private record PanelSelectionMeta(
            String universeLayer,
            String selectionReason,
            boolean dedupApplied,
            BigDecimal diversityScore,
            boolean coreThemeFilterApplied,
            String themeCode,
            String strategyKey,
            String panelPurpose,
            String primaryMetricLabel,
            BigDecimal primaryMetricValue,
            String stateBadge,
            String stateReason,
            String recommendationState,
            Boolean qualityDegraded,
            String sortBasis) {

        static PanelSelectionMeta forPosition(PanelStrategyEvaluation eval) {
            return new PanelSelectionMeta(
                    null,
                    "panel=position,direct_asset_lookup=true",
                    false,
                    BigDecimal.ZERO,
                    false,
                    null,
                    eval == null ? "CHART_RESPONSE" : eval.strategyKey(),
                    eval == null ? "차트 대응/압력 확인" : eval.panelPurpose(),
                    eval == null ? "포지션관리" : eval.primaryMetricLabel(),
                    eval == null ? BigDecimal.ZERO : eval.primaryMetricValue(),
                    eval == null ? "관찰" : eval.stateBadge(),
                    eval == null ? "단일 자산 직접 조회" : eval.stateReason(),
                    eval == null ? "WATCH_ONLY" : eval.recommendationState(),
                    eval != null && eval.qualityDegraded(),
                    eval == null ? "position_signal>chart_confidence" : eval.sortBasis());
        }

        static PanelSelectionMeta fallback(PanelType panelType, String themeCode) {
            return new PanelSelectionMeta(
                    null,
                    "panel=" + panelType.name().toLowerCase(Locale.ROOT) + ",fallback=true",
                    true,
                    BigDecimal.ZERO,
                    !isBlankStatic(themeCode) && isPriorityThemeStatic(themeCode),
                    themeCode,
                    panelType.name(),
                    panelType.name().toLowerCase(Locale.ROOT) + " fallback",
                    "점수",
                    BigDecimal.ZERO,
                    "fallback",
                    "엄격한 중복 억제로 인해 fallback 선택",
                    "WATCH_ONLY",
                    true,
                    "fallback:generated_at");
        }

        PanelSelectionMeta withDedupApplied(boolean value) {
            return new PanelSelectionMeta(
                    universeLayer,
                    selectionReason,
                    value,
                    diversityScore,
                    coreThemeFilterApplied,
                    themeCode,
                    strategyKey,
                    panelPurpose,
                    primaryMetricLabel,
                    primaryMetricValue,
                    stateBadge,
                    stateReason,
                    recommendationState,
                    qualityDegraded,
                    sortBasis);
        }

        private static boolean isBlankStatic(String value) {
            return value == null || value.isBlank();
        }

        private static boolean isPriorityThemeStatic(String themeCode) {
            if (themeCode == null) {
                return false;
            }
            return Set.of("RESOURCE", "DEFENSE", "SPACE", "AI", "SEMICONDUCTOR", "ROBOTICS", "ENERGY")
                    .contains(themeCode);
        }
    }
}

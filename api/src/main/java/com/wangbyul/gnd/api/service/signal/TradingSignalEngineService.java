package com.wangbyul.gnd.api.service.signal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.dto.PressureAnalysisDto;
import com.wangbyul.gnd.api.dto.SignalDetailDto;
import com.wangbyul.gnd.api.dto.TradingSignalViewDto;
import com.wangbyul.gnd.api.dto.WeeklyContextDto;
import com.wangbyul.gnd.api.service.SystemFeatureToggleService;
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
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.StrategyRunRepository;
import com.wangbyul.gnd.core.repository.TradingSignalRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시그널 엔진 오케스트레이터.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class TradingSignalEngineService {

    private final AssetUniverseRepository assetUniverseRepository;
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

    public TradingSignalEngineService(
            AssetUniverseRepository assetUniverseRepository,
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
            SystemFeatureToggleService systemFeatureToggleService) {
        this.assetUniverseRepository = assetUniverseRepository;
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

    public List<TradingSignalViewDto> getScalpSignals(String country, String theme, int limit) {
        ensureRecentSignals(country, theme, Math.max(limit, 20), StrategyRunType.SCALP);
        List<SignalActionType> actions = List.of(SignalActionType.BUY_CANDIDATE, SignalActionType.SELL_CANDIDATE, SignalActionType.WATCH);
        return queryByActions(country, theme, actions, limit);
    }

    public List<TradingSignalViewDto> getSwingSignals(String country, String theme, int limit) {
        ensureRecentSignals(country, theme, Math.max(limit, 20), StrategyRunType.SWING);
        List<SignalActionType> actions = List.of(SignalActionType.BUY_CANDIDATE, SignalActionType.SELL_CANDIDATE, SignalActionType.HOLD, SignalActionType.WATCH);
        return queryByActions(country, theme, actions, limit);
    }

    public TradingSignalViewDto getPositionSignal(String assetCode) {
        tradingSignalRepository.findTop1ByAssetCodeOrderByGeneratedAtDesc(assetCode)
                .orElseGet(() -> {
                    AssetUniverseEntity asset = assetUniverseRepository.findById(assetCode)
                            .orElseThrow(() -> new IllegalArgumentException("asset not found: " + assetCode));
                    return computeAndSave(asset, StrategyRunType.POSITION);
                });
        TradingSignalEntity signal = tradingSignalRepository.findTop1ByAssetCodeOrderByGeneratedAtDesc(assetCode)
                .orElseThrow(() -> new IllegalArgumentException("signal not found: " + assetCode));
        return toViewDto(signal);
    }

    public List<TradingSignalViewDto> getDiscoverySignals(String country, String theme, int limit) {
        ensureRecentSignals(country, theme, Math.max(limit, 30), StrategyRunType.DISCOVERY);
        return tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                        country,
                        OffsetDateTime.now().minusHours(6),
                        PageRequest.of(0, 200))
                .stream()
                .filter(signal -> theme == null || theme.isBlank() || Objects.equals(theme, signal.getTheme()))
                .sorted(Comparator.comparing(TradingSignalEntity::getDiscoveryScore).reversed())
                .limit(limit)
                .map(this::toViewDto)
                .toList();
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
        TradingSignalEntity signal = tradingSignalRepository.findById(signalId)
                .orElseThrow(() -> new IllegalArgumentException("signal not found: " + signalId));
        AssetUniverseEntity asset = assetUniverseRepository.findById(signal.getAssetCode()).orElse(null);
        List<String> riskChecks = parseRiskChecks(signal.getRiskChecks());
        PressureAnalysisDto pressure = getPressureAnalysis(signal.getAssetCode());
        return SignalDetailDto.builder()
                .signalId(signal.getId())
                .assetCode(signal.getAssetCode())
                .assetName(asset == null ? "-" : asset.getAssetName())
                .action(signal.getAction())
                .goodNewsProbability(signal.getGoodNewsProbability())
                .badNewsProbability(signal.getBadNewsProbability())
                .weeklyContextScore(signal.getWeeklyContextScore())
                .combinedConfidence(signal.getCombinedConfidence())
                .probabilityReasonBreakdownJson(signal.getProbabilityReasonBreakdownJson())
                .pressureReasonJson(signal.getPressureReasonJson())
                .newsEvidence(List.of("최근 1주 뉴스량/긍부정 비율 기반"))
                .chartEvidence(List.of("MA/추세/변동성/거래량 기반"))
                .pressureAnalysis(pressure)
                .riskChecks(riskChecks)
                .blockedReason(signal.getBlockedReason())
                .reasonJson(signal.getReasonJson())
                .generatedAt(signal.getGeneratedAt())
                .build();
    }

    private List<AssetUniverseEntity> resolveAssets(String country, String theme, int limit) {
        List<AssetUniverseEntity> assets;
        if (theme == null || theme.isBlank()) {
            assets = assetUniverseRepository.findByCountryAndActiveTrueOrderByDisplayWeightDescSelectionScoreDescUpdatedAtDesc(country);
            if (assets.isEmpty()) {
                assets = assetUniverseRepository.findTop200ByCountryAndActiveTrueOrderByUpdatedAtDesc(country);
            }
        } else {
            assets = assetUniverseRepository.findByCountryAndThemeAndActiveTrueOrderByDisplayWeightDescSelectionScoreDescUpdatedAtDesc(
                    country,
                    theme);
            if (assets.isEmpty()) {
                assets = assetUniverseRepository.findTop200ByCountryAndThemeAndActiveTrueOrderByUpdatedAtDesc(country, theme);
            }
        }
        return assets.stream().limit(Math.max(1, limit)).toList();
    }

    private void ensureRecentSignals(String country, String theme, int limit, StrategyRunType runType) {
        List<TradingSignalEntity> existing = tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                country,
                OffsetDateTime.now().minusMinutes(20),
                PageRequest.of(0, 10));
        boolean exists = existing.stream().anyMatch(signal -> theme == null || theme.isBlank() || Objects.equals(theme, signal.getTheme()));
        if (!exists) {
            generateSignals(country, theme, limit, runType);
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

    private List<TradingSignalViewDto> queryByActions(String country, String theme, List<SignalActionType> actions, int limit) {
        List<TradingSignalEntity> rows = (theme == null || theme.isBlank())
                ? tradingSignalRepository.findByCountryAndActionInOrderByGeneratedAtDesc(country, actions, PageRequest.of(0, limit))
                : tradingSignalRepository.findByCountryAndThemeAndActionInOrderByGeneratedAtDesc(country, theme, actions, PageRequest.of(0, limit));
        return rows.stream().map(this::toViewDto).toList();
    }

    private TradingSignalViewDto toViewDto(TradingSignalEntity signal) {
        String assetName = assetUniverseRepository.findById(signal.getAssetCode()).map(AssetUniverseEntity::getAssetName).orElse("-");
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
                .generatedAt(signal.getGeneratedAt())
                .build();
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

    private String traceId() {
        String trace = MDC.get("trace_id");
        return trace == null ? "" : trace;
    }
}

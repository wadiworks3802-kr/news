package com.wangbyul.gnd.api.service.signal;

import com.wangbyul.gnd.api.config.RiskPolicyProperties;
import com.wangbyul.gnd.api.dto.BuyLockStatusDto;
import com.wangbyul.gnd.api.dto.ExposureItemDto;
import com.wangbyul.gnd.api.dto.PaperTradeRiskDto;
import com.wangbyul.gnd.api.service.StrategyConfigService;
import com.wangbyul.gnd.api.service.signal.model.RiskDecision;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.PaperTradePositionEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.PaperTradePositionRepository;
import com.wangbyul.gnd.core.repository.TradingSignalRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 몰빵 금지/노출 한도/잠금 정책 판정 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class RiskPolicyService {

    private final RiskPolicyProperties riskPolicyProperties;
    private final PaperTradePositionRepository paperTradePositionRepository;
    private final AssetUniverseRepository assetUniverseRepository;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final TradingSignalRepository tradingSignalRepository;
    private final ReanalysisLockService reanalysisLockService;
    private final StrategyConfigService strategyConfigService;

    public RiskPolicyService(
            RiskPolicyProperties riskPolicyProperties,
            PaperTradePositionRepository paperTradePositionRepository,
            AssetUniverseRepository assetUniverseRepository,
            MarketQuoteSnapshotRepository marketQuoteSnapshotRepository,
            TradingSignalRepository tradingSignalRepository,
            ReanalysisLockService reanalysisLockService,
            StrategyConfigService strategyConfigService) {
        this.riskPolicyProperties = riskPolicyProperties;
        this.paperTradePositionRepository = paperTradePositionRepository;
        this.assetUniverseRepository = assetUniverseRepository;
        this.marketQuoteSnapshotRepository = marketQuoteSnapshotRepository;
        this.tradingSignalRepository = tradingSignalRepository;
        this.reanalysisLockService = reanalysisLockService;
        this.strategyConfigService = strategyConfigService;
    }

    public RiskDecision evaluate(
            AssetUniverseEntity asset,
            SignalActionType action,
            BigDecimal requestedRatio) {
        List<String> checks = new ArrayList<>();

        if (action != SignalActionType.BUY_CANDIDATE) {
            checks.add("ACTION_NOT_BUY:PASS");
            return new RiskDecision(true, action, checks, null);
        }

        if (reanalysisLockService.isBuyLocked(asset.getAssetCode())) {
            checks.add("BUY_LOCK:FAIL");
            return new RiskDecision(false, SignalActionType.BUY_LOCK, checks, "BUY_LOCK active");
        }
        checks.add("BUY_LOCK:PASS");

        BigDecimal capital = nvl(effectiveCapitalTotal());
        BigDecimal invested = computeInvestedAmount();
        BigDecimal request = capital.multiply(nvl(requestedRatio));
        BigDecimal investedAfter = invested.add(request);

        int openPositions = (int) paperTradePositionRepository.findAll().stream()
                .filter(position -> nvl(position.getQuantity()).compareTo(BigDecimal.ZERO) > 0)
                .count();
        if (openPositions >= Math.max(1, effectiveMaxOpenPositions())) {
            checks.add("MAX_OPEN_POSITIONS:FAIL");
            return new RiskDecision(false, SignalActionType.HOLD, checks, "동시 보유 종목 수 한도 초과");
        }
        checks.add("MAX_OPEN_POSITIONS:PASS");

        BigDecimal assetAmount = currentAssetAmount(asset.getAssetCode()).add(request);
        BigDecimal assetRatio = ratio(assetAmount, investedAfter);
        if (assetRatio.compareTo(nvl(effectiveMaxPositionRatioPerAsset())) > 0) {
            checks.add("ASSET_RATIO:FAIL");
            return new RiskDecision(false, SignalActionType.HOLD, checks, "종목 비중 한도 초과");
        }
        checks.add("ASSET_RATIO:PASS");

        BigDecimal themeAmount = currentThemeAmount(asset.getTheme()).add(request);
        BigDecimal themeRatio = ratio(themeAmount, investedAfter);
        if (themeRatio.compareTo(nvl(effectiveMaxThemeExposureRatio())) > 0) {
            checks.add("THEME_RATIO:FAIL");
            return new RiskDecision(false, SignalActionType.HOLD, checks, "테마 비중 한도 초과");
        }
        checks.add("THEME_RATIO:PASS");

        BigDecimal countryAmount = currentCountryAmount(asset.getCountry()).add(request);
        BigDecimal countryRatio = ratio(countryAmount, investedAfter);
        if (countryRatio.compareTo(nvl(effectiveMaxCountryExposureRatio())) > 0) {
            checks.add("COUNTRY_RATIO:FAIL");
            return new RiskDecision(false, SignalActionType.HOLD, checks, "국가 비중 한도 초과");
        }
        checks.add("COUNTRY_RATIO:PASS");

        return new RiskDecision(true, action, checks, null);
    }

    public PaperTradeRiskDto portfolioRisk() {
        return portfolioRisk(null);
    }

    public PaperTradeRiskDto portfolioRisk(BigDecimal capitalOverride) {
        BigDecimal capital = nvl(capitalOverride != null && capitalOverride.compareTo(BigDecimal.ZERO) > 0
                ? capitalOverride
                : effectiveCapitalTotal());
        BigDecimal invested = computeInvestedAmount();
        BigDecimal cash = capital.subtract(invested);
        List<PaperTradePositionEntity> positions = paperTradePositionRepository.findAll();
        List<BuyLockStatusDto> locks = buyLocks();
        StrategyPanelRiskSnapshot strategySnapshot = strategyPanelRiskSnapshot();
        List<String> degradedAssets = dataQualityDegradedAssets(positions);
        List<String> positionWarnings = positionLimitWarningAssets(invested, positions);
        List<String> diversificationWarnings = portfolioDiversificationWarnings(invested);
        List<Integer> buySplits = effectiveBuySplitRules();
        List<Integer> sellSplits = effectiveSellSplitRules();
        BigDecimal recommendedEntryRatioPct = recommendEntryRatioPct();
        BigDecimal recommendedEntryAmount = capital.multiply(
                        recommendedEntryRatioPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))
                .setScale(2, RoundingMode.HALF_UP);

        return PaperTradeRiskDto.builder()
                .capitalTotal(capital)
                .investedAmount(invested)
                .cashRemaining(cash.max(BigDecimal.ZERO))
                .maxPositionRatioPerAsset(effectiveMaxPositionRatioPerAsset())
                .maxThemeExposureRatio(effectiveMaxThemeExposureRatio())
                .maxCountryExposureRatio(effectiveMaxCountryExposureRatio())
                .maxOpenPositions(effectiveMaxOpenPositions())
                .takeProfitPct(effectiveTakeProfitPct())
                .stopLossPct(effectiveStopLossPct())
                .positionExposure(exposureByAsset(invested))
                .themeExposure(exposureByTheme(invested))
                .countryExposure(exposureByCountry(invested))
                .strategyActionDistribution(strategySnapshot.strategyActionDistribution())
                .strategyBlockedCount(strategySnapshot.strategyBlockedCount())
                .blockedReasonDistribution(strategySnapshot.blockedReasonDistribution())
                .duplicateExposureStats(strategySnapshot.duplicateExposureStats())
                .buyLockCount(locks.size())
                .reanalysisPendingCount((int) locks.stream().filter(this::isReanalysisPending).count())
                .dataQualityDegradedAssets(degradedAssets)
                .positionLimitWarningAssets(positionWarnings)
                .portfolioDiversificationWarning(!diversificationWarnings.isEmpty())
                .portfolioDiversificationWarnings(diversificationWarnings)
                .referenceOnly(true)
                .referenceCapitalBasis(capital)
                .recommendedEntryRatioPct(recommendedEntryRatioPct)
                .recommendedEntryAmount(recommendedEntryAmount)
                .recommendedBuySplitRatios(buySplits)
                .recommendedSellSplitRatios(sellSplits)
                .reanalysisLockMinutes(effectiveReanalysisLockMinutes())
                .build();
    }

    public List<BuyLockStatusDto> buyLocks() {
        return paperTradePositionRepository.findTop300ByBuyLockTrueOrderByLockUntilAsc().stream()
                .map(position -> BuyLockStatusDto.builder()
                        .assetCode(position.getAssetCode())
                        .assetName(assetUniverseRepository.findById(position.getAssetCode()).map(AssetUniverseEntity::getAssetName).orElse("-"))
                        .lockReason(position.getLockReason())
                        .lockUntil(position.getLockUntil())
                        .lastReanalysisAt(position.getLastReanalysisAt())
                        .build())
                .toList();
    }

    private BigDecimal computeInvestedAmount() {
        return paperTradePositionRepository.findAll().stream()
                .map(this::positionAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal positionAmount(PaperTradePositionEntity position) {
        BigDecimal price = nvl(position.getCurrentPrice());
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            price = marketQuoteSnapshotRepository.findTop1ByAssetCodeOrderBySnapshotUtcDesc(position.getAssetCode())
                    .map(quote -> nvl(quote.getLastPrice()))
                    .orElse(nvl(position.getAvgPrice()));
        }
        return nvl(position.getQuantity()).multiply(price);
    }

    private BigDecimal currentAssetAmount(String assetCode) {
        return paperTradePositionRepository.findByAssetCode(assetCode)
                .map(this::positionAmount)
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal currentThemeAmount(String theme) {
        if (theme == null || theme.isBlank()) {
            return BigDecimal.ZERO;
        }
        List<String> assets = assetUniverseRepository.findTop200ByThemeAndActiveTrueOrderByUpdatedAtDesc(theme).stream()
                .map(AssetUniverseEntity::getAssetCode)
                .toList();
        BigDecimal total = BigDecimal.ZERO;
        for (String assetCode : assets) {
            total = total.add(currentAssetAmount(assetCode));
        }
        return total;
    }

    private BigDecimal currentCountryAmount(String country) {
        List<String> assets = assetUniverseRepository.findTop200ByCountryAndActiveTrueOrderByUpdatedAtDesc(country).stream()
                .map(AssetUniverseEntity::getAssetCode)
                .toList();
        BigDecimal total = BigDecimal.ZERO;
        for (String assetCode : assets) {
            total = total.add(currentAssetAmount(assetCode));
        }
        return total;
    }

    private List<ExposureItemDto> exposureByAsset(BigDecimal invested) {
        List<PaperTradePositionEntity> positions = paperTradePositionRepository.findAll();
        return positions.stream()
                .map(position -> {
                    BigDecimal amount = positionAmount(position);
                    return ExposureItemDto.builder()
                            .key(position.getAssetCode())
                            .amount(amount)
                            .ratio(ratio(amount, invested))
                            .build();
                })
                .sorted(Comparator.comparing(ExposureItemDto::getRatio).reversed())
                .limit(20)
                .toList();
    }

    private List<ExposureItemDto> exposureByTheme(BigDecimal invested) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (PaperTradePositionEntity position : paperTradePositionRepository.findAll()) {
            String theme = assetUniverseRepository.findById(position.getAssetCode()).map(AssetUniverseEntity::getTheme).orElse("UNKNOWN");
            totals.merge(theme == null ? "UNKNOWN" : theme, positionAmount(position), BigDecimal::add);
        }
        return totals.entrySet().stream()
                .map(entry -> ExposureItemDto.builder()
                        .key(entry.getKey())
                        .amount(entry.getValue())
                        .ratio(ratio(entry.getValue(), invested))
                        .build())
                .sorted(Comparator.comparing(ExposureItemDto::getRatio).reversed())
                .collect(Collectors.toList());
    }

    private List<ExposureItemDto> exposureByCountry(BigDecimal invested) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (PaperTradePositionEntity position : paperTradePositionRepository.findAll()) {
            String country = assetUniverseRepository.findById(position.getAssetCode()).map(AssetUniverseEntity::getCountry).orElse("N/A");
            totals.merge(country, positionAmount(position), BigDecimal::add);
        }
        return totals.entrySet().stream()
                .map(entry -> ExposureItemDto.builder()
                        .key(entry.getKey())
                        .amount(entry.getValue())
                        .ratio(ratio(entry.getValue(), invested))
                        .build())
                .sorted(Comparator.comparing(ExposureItemDto::getRatio).reversed())
                .collect(Collectors.toList());
    }

    private StrategyPanelRiskSnapshot strategyPanelRiskSnapshot() {
        List<TradingSignalEntity> recent = tradingSignalRepository.findByGeneratedAtAfterOrderByGeneratedAtDesc(
                OffsetDateTime.now().minusHours(24),
                org.springframework.data.domain.PageRequest.of(0, 400));

        Map<String, Map<String, Long>> actionByStrategy = new LinkedHashMap<>();
        Map<String, Long> blockedByStrategy = new LinkedHashMap<>();
        Map<String, Long> blockedReasonDistribution = new LinkedHashMap<>();
        Map<String, Long> duplicateStats = new LinkedHashMap<>();
        duplicateStats.put("duplicate_asset_rows", 0L);
        duplicateStats.put("duplicate_asset_window_rows", 0L);

        Map<String, Integer> seenAsset = new HashMap<>();
        Map<String, Integer> seenAssetWindow = new HashMap<>();
        for (TradingSignalEntity row : recent) {
            String strategy = strategyKey(row);
            String action = row.getAction() == null ? "UNKNOWN" : row.getAction().name();
            actionByStrategy.computeIfAbsent(strategy, k -> new LinkedHashMap<>())
                    .merge(action, 1L, Long::sum);
            if (row.getBlockedReason() != null && !row.getBlockedReason().isBlank()) {
                blockedByStrategy.merge(strategy, 1L, Long::sum);
                for (String token : row.getBlockedReason().split("\\|")) {
                    String key = token == null || token.isBlank() ? "UNKNOWN" : token.trim();
                    blockedReasonDistribution.merge(key, 1L, Long::sum);
                }
            }
            String assetKey = row.getAssetCode() == null ? "" : row.getAssetCode();
            if (!assetKey.isBlank()) {
                seenAsset.merge(assetKey, 1, Integer::sum);
                String windowKey = assetKey + "|" + (row.getSignalWindow() == null ? "N/A" : row.getSignalWindow());
                seenAssetWindow.merge(windowKey, 1, Integer::sum);
            }
        }
        duplicateStats.put(
                "duplicate_asset_rows",
                seenAsset.values().stream().filter(v -> v != null && v > 1).count());
        duplicateStats.put(
                "duplicate_asset_window_rows",
                seenAssetWindow.values().stream().filter(v -> v != null && v > 1).count());

        return new StrategyPanelRiskSnapshot(actionByStrategy, blockedByStrategy, blockedReasonDistribution, duplicateStats);
    }

    private String strategyKey(TradingSignalEntity row) {
        String window = row == null || row.getSignalWindow() == null ? "" : row.getSignalWindow().trim().toLowerCase();
        return switch (window) {
            case "1h" -> "SCALP";
            case "1w" -> "SWING";
            case "6m" -> "DISCOVERY";
            case "1m" -> "CHART_RESPONSE";
            default -> "OTHER";
        };
    }

    private boolean isReanalysisPending(BuyLockStatusDto dto) {
        if (dto == null) {
            return false;
        }
        if (dto.getLastReanalysisAt() == null) {
            return true;
        }
        if (dto.getLockUntil() == null) {
            return false;
        }
        return dto.getLastReanalysisAt().isBefore(dto.getLockUntil().minusMinutes(1));
    }

    private List<String> dataQualityDegradedAssets(List<PaperTradePositionEntity> positions) {
        List<String> rows = new ArrayList<>();
        for (PaperTradePositionEntity position : positions) {
            String assetCode = position.getAssetCode();
            AssetUniverseEntity asset = assetUniverseRepository.findById(assetCode).orElse(null);
            if (asset == null) {
                rows.add(assetCode + " (유니버스 누락)");
                continue;
            }
            List<String> reasons = new ArrayList<>();
            if (!Boolean.TRUE.equals(asset.getIsTradeEnabled())) {
                reasons.add("trade_disabled");
            }
            if (asset.getLastQuoteReceivedAt() == null || asset.getLastQuoteReceivedAt().isBefore(OffsetDateTime.now().minusHours(3))) {
                reasons.add("stale_quote");
            }
            if (asset.getVerificationStatus() != null && "FAILED".equals(asset.getVerificationStatus().name())) {
                reasons.add("verification_failed");
            }
            if (!reasons.isEmpty()) {
                rows.add(asset.getAssetName() + " (" + asset.getAssetCode() + "): " + String.join(",", reasons));
            }
        }
        return rows.stream().limit(20).toList();
    }

    private List<String> positionLimitWarningAssets(BigDecimal invested, List<PaperTradePositionEntity> positions) {
        BigDecimal limit = nvl(effectiveMaxPositionRatioPerAsset());
        if (limit.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }
        BigDecimal warnLine = limit.multiply(BigDecimal.valueOf(0.85d)).setScale(6, RoundingMode.HALF_UP);
        List<String> warnings = new ArrayList<>();
        for (PaperTradePositionEntity position : positions) {
            BigDecimal amount = positionAmount(position);
            BigDecimal ratio = ratio(amount, invested);
            if (ratio.compareTo(warnLine) >= 0) {
                String name = assetUniverseRepository.findById(position.getAssetCode())
                        .map(AssetUniverseEntity::getAssetName)
                        .orElse(position.getAssetCode());
                warnings.add(name + " (" + position.getAssetCode() + ") "
                        + ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%");
            }
        }
        return warnings.stream().sorted().toList();
    }

    private List<String> portfolioDiversificationWarnings(BigDecimal invested) {
        List<String> warnings = new ArrayList<>();
        List<ExposureItemDto> assetExposure = exposureByAsset(invested);
        List<ExposureItemDto> themeExposure = exposureByTheme(invested);
        List<ExposureItemDto> countryExposure = exposureByCountry(invested);
        BigDecimal assetLimit = nvl(effectiveMaxPositionRatioPerAsset());
        BigDecimal themeLimit = nvl(effectiveMaxThemeExposureRatio());
        BigDecimal countryLimit = nvl(effectiveMaxCountryExposureRatio());

        if (!assetExposure.isEmpty() && assetLimit.compareTo(BigDecimal.ZERO) > 0
                && nvl(assetExposure.get(0).getRatio()).compareTo(assetLimit.multiply(BigDecimal.valueOf(0.90d))) >= 0) {
            warnings.add("상위 종목 비중이 종목 한도 90% 이상입니다.");
        }
        if (!themeExposure.isEmpty() && themeLimit.compareTo(BigDecimal.ZERO) > 0
                && nvl(themeExposure.get(0).getRatio()).compareTo(themeLimit.multiply(BigDecimal.valueOf(0.90d))) >= 0) {
            warnings.add("테마 집중도가 높아 분산 경고입니다.");
        }
        if (!countryExposure.isEmpty() && countryLimit.compareTo(BigDecimal.ZERO) > 0
                && nvl(countryExposure.get(0).getRatio()).compareTo(countryLimit.multiply(BigDecimal.valueOf(0.90d))) >= 0) {
            warnings.add("국가 집중도가 높아 분산 경고입니다.");
        }
        if (paperTradePositionRepository.findAll().stream()
                .filter(position -> nvl(position.getQuantity()).compareTo(BigDecimal.ZERO) > 0)
                .count() <= 1) {
            warnings.add("보유 종목 수가 적어 몰빵 위험이 높습니다.");
        }
        return warnings;
    }

    private BigDecimal recommendEntryRatioPct() {
        List<Integer> buySplits = effectiveBuySplitRules();
        BigDecimal maxAssetPct = nvl(effectiveMaxPositionRatioPerAsset()).multiply(BigDecimal.valueOf(100));
        BigDecimal splitPct = buySplits.isEmpty() ? BigDecimal.valueOf(20) : BigDecimal.valueOf(Math.max(1, buySplits.get(0)));
        return splitPct.min(maxAssetPct.max(BigDecimal.valueOf(1))).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal amount, BigDecimal total) {
        if (total == null || total.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.divide(total, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal effectiveCapitalTotal() {
        return strategyConfigService.getDecimal("app.risk.capital-total", riskPolicyProperties.getCapitalTotal());
    }

    public BigDecimal effectiveMaxPositionRatioPerAsset() {
        return strategyConfigService.getDecimal(
                "app.risk.max-position-ratio-per-asset",
                riskPolicyProperties.getMaxPositionRatioPerAsset());
    }

    public BigDecimal effectiveMaxThemeExposureRatio() {
        return strategyConfigService.getDecimal(
                "app.risk.max-theme-exposure-ratio",
                riskPolicyProperties.getMaxThemeExposureRatio());
    }

    public BigDecimal effectiveMaxCountryExposureRatio() {
        return strategyConfigService.getDecimal(
                "app.risk.max-country-exposure-ratio",
                riskPolicyProperties.getMaxCountryExposureRatio());
    }

    public Integer effectiveMaxOpenPositions() {
        return strategyConfigService.getInteger(
                "app.risk.max-open-positions",
                riskPolicyProperties.getMaxOpenPositions());
    }

    public BigDecimal effectiveTakeProfitPct() {
        return strategyConfigService.getDecimal(
                "app.risk.take-profit-pct",
                riskPolicyProperties.getTakeProfitPct());
    }

    public BigDecimal effectiveStopLossPct() {
        return strategyConfigService.getDecimal(
                "app.risk.stop-loss-pct",
                riskPolicyProperties.getStopLossPct());
    }

    public Boolean effectiveReanalysisLockAfterTpSl() {
        return strategyConfigService.getBoolean(
                "app.risk.reanalysis-lock-after-tp-sl",
                riskPolicyProperties.getReanalysisLockAfterTpSl());
    }

    public Integer effectiveReanalysisLockMinutes() {
        return strategyConfigService.getInteger(
                "app.risk.reanalysis-lock-minutes",
                riskPolicyProperties.getReanalysisLockMinutes());
    }

    public List<Integer> effectiveBuySplitRules() {
        return strategyConfigService.getIntegerList(
                "app.risk.buy-split-rules",
                riskPolicyProperties.getBuySplitRules());
    }

    public List<Integer> effectiveSellSplitRules() {
        return strategyConfigService.getIntegerList(
                "app.risk.sell-split-rules",
                riskPolicyProperties.getSellSplitRules());
    }

    private record StrategyPanelRiskSnapshot(
            Map<String, Map<String, Long>> strategyActionDistribution,
            Map<String, Long> strategyBlockedCount,
            Map<String, Long> blockedReasonDistribution,
            Map<String, Long> duplicateExposureStats) {
    }
}

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
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.PaperTradePositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private final ReanalysisLockService reanalysisLockService;
    private final StrategyConfigService strategyConfigService;

    public RiskPolicyService(
            RiskPolicyProperties riskPolicyProperties,
            PaperTradePositionRepository paperTradePositionRepository,
            AssetUniverseRepository assetUniverseRepository,
            MarketQuoteSnapshotRepository marketQuoteSnapshotRepository,
            ReanalysisLockService reanalysisLockService,
            StrategyConfigService strategyConfigService) {
        this.riskPolicyProperties = riskPolicyProperties;
        this.paperTradePositionRepository = paperTradePositionRepository;
        this.assetUniverseRepository = assetUniverseRepository;
        this.marketQuoteSnapshotRepository = marketQuoteSnapshotRepository;
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
        BigDecimal capital = nvl(effectiveCapitalTotal());
        BigDecimal invested = computeInvestedAmount();
        BigDecimal cash = capital.subtract(invested);

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
}

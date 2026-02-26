package com.wangbyul.gnd.core.service;

import com.wangbyul.gnd.core.domain.AssetSelectionSourceType;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.AssetVerificationStatusType;
import com.wangbyul.gnd.core.domain.MarketDataQualitySnapshotEntity;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
import com.wangbyul.gnd.core.domain.UniverseLayerType;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketDataQualitySnapshotRepository;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.NewsAssetLinkRepository;
import com.wangbyul.gnd.core.repository.TradingSignalRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 국가/테마 기반 유니버스 재구성 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 정책 핵심:
 * - 국가/테마 최소 종목 수 보장
 * - 동일 티커 패밀리 과다 반복 제한
 * - 최근 노출 과다 종목 패널티
 * - 품질 점수 낮은 자산은 핵심 종목에서 제외
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UniverseRebuildService {

    private final AssetUniverseRepository assetUniverseRepository;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final MarketPriceBarRepository marketPriceBarRepository;
    private final MarketDataQualitySnapshotRepository marketDataQualitySnapshotRepository;
    private final NewsAssetLinkRepository newsAssetLinkRepository;
    private final TradingSignalRepository tradingSignalRepository;

    @Value("${app.universe.min-assets-per-country:5}")
    private int minAssetsPerCountry;

    @Value("${app.universe.min-assets-per-theme:3}")
    private int minAssetsPerTheme;

    @Value("${app.universe.core-assets-per-country:8}")
    private int coreAssetsPerCountry;

    @Value("${app.universe.max-same-family-exposure:2}")
    private int maxSameFamilyExposure;

    @Value("${app.universe.repeat-penalty-lookback-days:7}")
    private int repeatPenaltyLookbackDays;

    @Value("${app.universe.repeat-penalty-per-exposure:1.5}")
    private BigDecimal repeatPenaltyPerExposure;

    @Value("${app.universe.min-quality-score-for-core:70}")
    private BigDecimal minQualityScoreForCore;

    @Value("${app.universe.priority-themes:RESOURCE,DEFENSE,SPACE,AI,SEMICONDUCTOR,ROBOTICS,ENERGY}")
    private List<String> priorityThemes;

    @Value("${app.universe.default-dup-exposure-cooldown-minutes:60}")
    private int defaultDupExposureCooldownMinutes;

    @Value("${app.universe.quote-freshness-threshold-minutes:180}")
    private int quoteFreshnessThresholdMinutes;

    @Value("${app.universe.signal-freshness-threshold-hours:48}")
    private int signalFreshnessThresholdHours;

    @Value("${app.universe.news-link-freshness-threshold-hours:168}")
    private int newsLinkFreshnessThresholdHours;

    @Value("${app.universe.theme-leader-per-priority-theme:2}")
    private int themeLeaderPerPriorityTheme;

    @Value("${app.universe.volatility-lookback-bars:20}")
    private int volatilityLookbackBars;

    @Value("${app.universe.volatility-max-range-pct:0.20}")
    private BigDecimal volatilityMaxRangePct;

    @Transactional
    public UniverseRebuildResult rebuildUniverse(String triggeredBy) {
        List<AssetUniverseEntity> assets = assetUniverseRepository.findByActiveTrueOrderByUpdatedAtDesc();
        if (assets.isEmpty()) {
            return new UniverseRebuildResult(0, 0, 0, 0, traceId(), triggeredBy, OffsetDateTime.now());
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime mentionSince = now.minusDays(7);
        OffsetDateTime exposureSince = now.minusDays(Math.max(1, repeatPenaltyLookbackDays));

        Map<String, Long> mentionCountByAsset = new HashMap<>();
        for (AssetUniverseEntity asset : assets) {
            mentionCountByAsset.put(
                    asset.getAssetCode(),
                    newsAssetLinkRepository.countByAssetCodeAndCreatedAtAfter(asset.getAssetCode(), mentionSince));
        }

        Map<String, BigDecimal> avgVolumeByAsset = calculateAverageVolumeByAsset(assets);
        Map<String, BigDecimal> volatilityByAsset = calculateVolatilityByAsset(assets);
        Map<String, Long> exposureCountByAsset = new HashMap<>();
        for (AssetUniverseEntity asset : assets) {
            exposureCountByAsset.put(
                    asset.getAssetCode(),
                    tradingSignalRepository.countByAssetCodeAndGeneratedAtAfter(asset.getAssetCode(), exposureSince));
        }
        Map<String, OffsetDateTime> lastQuoteReceivedAtByAsset = resolveLatestQuoteReceivedAtByAsset(assets);
        Map<String, OffsetDateTime> lastSignalGeneratedAtByAsset = resolveLatestSignalGeneratedAtByAsset(assets);
        Map<String, OffsetDateTime> lastNewsLinkedAtByAsset = resolveLatestNewsLinkedAtByAsset(assets);

        Map<String, BigDecimal> latestQualityScoreByScope = resolveLatestQualityScores();
        Map<String, List<AssetUniverseEntity>> assetsByCountry = assets.stream()
                .collect(Collectors.groupingBy(AssetUniverseEntity::getCountry));

        int totalCoreAssets = 0;
        int totalCountryMinFallback = 0;
        int totalThemeMinFallback = 0;
        int penalizedAssets = 0;

        for (Map.Entry<String, List<AssetUniverseEntity>> entry : assetsByCountry.entrySet()) {
            String country = entry.getKey();
            List<AssetUniverseEntity> countryAssets = entry.getValue();

            List<AssetRank> ranked = rankAssets(
                    countryAssets,
                    mentionCountByAsset,
                    avgVolumeByAsset,
                    volatilityByAsset,
                    exposureCountByAsset,
                    latestQualityScoreByScope,
                    country);

            for (int i = 0; i < ranked.size(); i++) {
                AssetRank rank = ranked.get(i);
                AssetUniverseEntity asset = rank.asset();
                asset.setSelectionScore(rank.score());
                asset.setSelectionSource(rank.selectionSource());
                asset.setAvgVolumeRank(i + 1);
                if (asset.getMarketCapRank() == null) {
                    asset.setMarketCapRank(i + 1);
                }
                asset.setThemeCode(normalizeThemeCode(asset.getTheme()));
                asset.setCountryCode(normalizeCountryCode(asset.getCountry()));
                asset.setIsUserWatch(Boolean.TRUE.equals(asset.getIsWatchlistAsset()) || Boolean.TRUE.equals(asset.getIsUserWatch()));
                asset.setDupExposureCooldownMinutes(resolveDupExposureCooldownMinutes(rank));
                asset.setStrategyScope(resolveStrategyScope(rank));
                normalizePanelExposureWindow(asset, now);
                asset.setLastQuoteReceivedAt(lastQuoteReceivedAtByAsset.get(asset.getAssetCode()));
                asset.setLastSignalGeneratedAt(lastSignalGeneratedAtByAsset.get(asset.getAssetCode()));
                asset.setLastNewsLinkedAt(lastNewsLinkedAtByAsset.get(asset.getAssetCode()));
                asset.setIsTradeEnabled(isTradeEligible(
                        asset,
                        rank.qualityScore(),
                        lastQuoteReceivedAtByAsset.get(asset.getAssetCode())));
                asset.setUniverseLayer(UniverseLayerType.DISCOVERY);
                asset.setDiversityScore(rank.diversityScore());
                asset.setSelectionReason(rank.selectionReason());
                asset.setIsCoreAsset(false);
                asset.setDisplayWeight(0);
                if (rank.repeatPenalty().compareTo(BigDecimal.ZERO) > 0) {
                    penalizedAssets++;
                }
            }

            List<AssetUniverseEntity> selectedCore = selectCoreAssetsWithDiversity(ranked);
            int guaranteedCountryCount = ensureCountryMinimum(countryAssets, selectedCore);
            int guaranteedThemeCount = ensureThemeMinimum(countryAssets, selectedCore);
            totalCountryMinFallback += guaranteedCountryCount;
            totalThemeMinFallback += guaranteedThemeCount;

            assignUniverseLayers(countryAssets, ranked, selectedCore);
            assignDisplayWeights(countryAssets);
            Map<String, AssetRank> rankedByAssetCode = ranked.stream()
                    .collect(Collectors.toMap(r -> r.asset().getAssetCode(), r -> r, (a, b) -> a));
            for (AssetUniverseEntity asset : countryAssets) {
                AssetRank rank = rankedByAssetCode.get(asset.getAssetCode());
                if (rank != null) {
                    asset.setStrategyScope(resolveStrategyScope(rank));
                }
            }
            totalCoreAssets += selectedCore.size();
        }

        assetUniverseRepository.saveAll(assets);
        return new UniverseRebuildResult(
                assets.size(),
                totalCoreAssets,
                totalCountryMinFallback,
                totalThemeMinFallback,
                traceId(),
                triggeredBy,
                now);
    }

    public Map<String, Object> universeDiagnostics(String country, String theme, int limit) {
        List<AssetUniverseEntity> filtered;
        if (country != null && !country.isBlank() && theme != null && !theme.isBlank()) {
            filtered = resolveByCountryAndThemeOrThemeCode(country, theme);
        } else if (country != null && !country.isBlank()) {
            filtered = assetUniverseRepository.findByCountryOrderBySelectionScoreDesc(country);
        } else {
            // Repository implementation/test stubs may return immutable lists.
            filtered = new ArrayList<>(assetUniverseRepository.findByActiveTrueOrderByUpdatedAtDesc());
            filtered.sort(Comparator.comparing(
                            AssetUniverseEntity::getSelectionScore,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(AssetUniverseEntity::getDisplayWeight, Comparator.nullsLast(Comparator.reverseOrder())));
        }

        List<Map<String, Object>> topAssets = filtered.stream()
                .limit(Math.max(1, limit))
                .map(asset -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("asset_code", asset.getAssetCode());
                    row.put("asset_name", asset.getAssetName());
                    row.put("country", asset.getCountry());
                    row.put("country_code", safeString(asset.getCountryCode(), safeString(asset.getCountry(), "N/A")));
                    row.put("theme", safeString(asset.getTheme(), "N/A"));
                    row.put("theme_code", safeString(asset.getThemeCode(), "N/A"));
                    row.put("strategy_scope", safeString(asset.getStrategyScope(), "ALL"));
                    row.put("universe_layer", asset.getUniverseLayer() == null ? "N/A" : asset.getUniverseLayer().name());
                    row.put("selection_source",
                            safeString(asset.getSelectionSource() == null ? null : asset.getSelectionSource().name(), "MANUAL"));
                    row.put("selection_score", safeDecimal(asset.getSelectionScore()));
                    row.put("diversity_score", safeDecimal(asset.getDiversityScore()));
                    row.put("selection_reason", safeString(asset.getSelectionReason(), ""));
                    row.put("display_weight", asset.getDisplayWeight() == null ? 0 : asset.getDisplayWeight());
                    row.put("is_core_asset", Boolean.TRUE.equals(asset.getIsCoreAsset()));
                    row.put("is_trade_enabled", Boolean.TRUE.equals(asset.getIsTradeEnabled()));
                    row.put("is_user_watch", Boolean.TRUE.equals(asset.getIsUserWatch()));
                    row.put("dup_exposure_cooldown_minutes",
                            asset.getDupExposureCooldownMinutes() == null ? 0 : asset.getDupExposureCooldownMinutes());
                    row.put("panel_exposure_count_24h", asset.getPanelExposureCount24h() == null ? 0 : asset.getPanelExposureCount24h());
                    row.put("last_panel_exposed_at", asset.getLastPanelExposedAt());
                    row.put("last_signal_generated_at", asset.getLastSignalGeneratedAt());
                    row.put("last_quote_received_at", asset.getLastQuoteReceivedAt());
                    row.put("last_news_linked_at", asset.getLastNewsLinkedAt());
                    row.put("verification_status",
                            safeString(asset.getVerificationStatus() == null ? null : asset.getVerificationStatus().name(), "UNVERIFIED"));
                    return row;
                })
                .toList();

        Map<String, Long> byCountry = filtered.stream()
                .collect(Collectors.groupingBy(AssetUniverseEntity::getCountry, Collectors.counting()));
        Map<String, Long> byTheme = filtered.stream()
                .collect(Collectors.groupingBy(
                        asset -> safeString(asset.getTheme(), "N/A"),
                        Collectors.counting()));
        Map<String, Long> byThemeCode = filtered.stream()
                .collect(Collectors.groupingBy(
                        asset -> safeString(asset.getThemeCode(), "N/A"),
                        Collectors.counting()));
        Map<String, Long> byLayer = filtered.stream()
                .collect(Collectors.groupingBy(
                        asset -> asset.getUniverseLayer() == null ? "N/A" : asset.getUniverseLayer().name(),
                        Collectors.counting()));
        Map<String, Long> byStrategyScope = filtered.stream()
                .collect(Collectors.groupingBy(
                        asset -> safeString(asset.getStrategyScope(), "ALL"),
                        Collectors.counting()));

        Map<String, Long> topFamilies = filtered.stream()
                .collect(Collectors.groupingBy(asset -> tickerFamily(asset.getAssetCode()), Collectors.counting()))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));

        long coreCount = filtered.stream().filter(asset -> Boolean.TRUE.equals(asset.getIsCoreAsset())).count();
        long watchlistCount = filtered.stream().filter(asset -> Boolean.TRUE.equals(asset.getIsWatchlistAsset())).count();
        long tradeEnabledCount = filtered.stream().filter(asset -> Boolean.TRUE.equals(asset.getIsTradeEnabled())).count();
        long staleQuoteCount = filtered.stream().filter(asset -> !hasFreshQuote(asset.getLastQuoteReceivedAt())).count();
        long staleSignalCount = filtered.stream().filter(asset -> !hasFreshSignal(asset.getLastSignalGeneratedAt())).count();
        long staleNewsCount = filtered.stream().filter(asset -> !hasFreshNewsLink(asset.getLastNewsLinkedAt())).count();
        long priorityThemeCount = filtered.stream().filter(asset -> isPriorityTheme(asset.getThemeCode())).count();
        long activeCooldownCount = filtered.stream().filter(this::hasActivePanelCooldown).count();
        long repeatedExposureAssetCount = filtered.stream()
                .filter(asset -> (asset.getPanelExposureCount24h() == null ? 0 : asset.getPanelExposureCount24h()) >= 3)
                .count();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope_country", safeString(country, "ALL"));
        response.put("scope_theme", safeString(theme, "ALL"));
        response.put("total_assets", filtered.size());
        response.put("core_assets", coreCount);
        response.put("watchlist_assets", watchlistCount);
        response.put("trade_enabled_assets", tradeEnabledCount);
        response.put("stale_quote_assets", staleQuoteCount);
        response.put("stale_signal_assets", staleSignalCount);
        response.put("stale_news_link_assets", staleNewsCount);
        response.put("priority_theme_assets", priorityThemeCount);
        response.put("assets_by_country", byCountry);
        response.put("assets_by_theme", byTheme);
        response.put("assets_by_theme_code", byThemeCode);
        response.put("assets_by_layer", byLayer);
        response.put("assets_by_strategy_scope", byStrategyScope);
        response.put("top_repeated_families", topFamilies);
        response.put("active_panel_cooldown_assets", activeCooldownCount);
        response.put("repeated_panel_exposure_assets", repeatedExposureAssetCount);
        response.put("minimum_rules", Map.of(
                "min_assets_per_country", Math.max(1, minAssetsPerCountry),
                "min_assets_per_theme", Math.max(1, minAssetsPerTheme),
                "max_same_family_exposure", Math.max(1, maxSameFamilyExposure),
                "default_dup_exposure_cooldown_minutes", Math.max(0, defaultDupExposureCooldownMinutes)));
        response.put("priority_themes", normalizedPriorityThemes());
        response.put("top_assets", topAssets);
        return response;
    }

    private List<AssetRank> rankAssets(
            List<AssetUniverseEntity> assets,
            Map<String, Long> mentionCountByAsset,
            Map<String, BigDecimal> avgVolumeByAsset,
            Map<String, BigDecimal> volatilityByAsset,
            Map<String, Long> exposureCountByAsset,
            Map<String, BigDecimal> latestQualityScoreByScope,
            String country) {
        List<AssetUniverseEntity> sortedByLiquidity = assets.stream()
                .sorted(Comparator.comparing(
                                AssetUniverseEntity::getLiquidityScore,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AssetUniverseEntity::getAssetCode))
                .toList();
        Map<String, Integer> marketCapRankByAsset = new HashMap<>();
        for (int i = 0; i < sortedByLiquidity.size(); i++) {
            marketCapRankByAsset.put(sortedByLiquidity.get(i).getAssetCode(), i + 1);
        }

        BigDecimal maxVolume = avgVolumeByAsset.values().stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ONE);
        long maxMentions = mentionCountByAsset.values().stream().mapToLong(Long::longValue).max().orElse(1L);

        List<AssetRank> ranked = new ArrayList<>();
        for (AssetUniverseEntity asset : assets) {
            String themeCode = normalizeThemeCode(asset.getTheme());
            BigDecimal liquidity = safeDecimal(asset.getLiquidityScore());
            BigDecimal liquidityScore = clamp01(liquidity).multiply(BigDecimal.valueOf(30));

            BigDecimal avgVolume = avgVolumeByAsset.getOrDefault(asset.getAssetCode(), BigDecimal.ZERO);
            BigDecimal volumeNormalized = maxVolume.compareTo(BigDecimal.ZERO) <= 0
                    ? BigDecimal.ZERO
                    : avgVolume.divide(maxVolume, 6, RoundingMode.HALF_UP).min(BigDecimal.ONE);
            BigDecimal volumeScore = volumeNormalized.multiply(BigDecimal.valueOf(35));

            BigDecimal volatilityNormalized = clamp01(volatilityByAsset.getOrDefault(asset.getAssetCode(), BigDecimal.ZERO));
            BigDecimal volatilityScore = volatilityNormalized.multiply(BigDecimal.valueOf(8));

            long mentions = mentionCountByAsset.getOrDefault(asset.getAssetCode(), 0L);
            BigDecimal mentionNormalized = BigDecimal.valueOf(mentions)
                    .divide(BigDecimal.valueOf(Math.max(1L, maxMentions)), 6, RoundingMode.HALF_UP);
            BigDecimal mentionScore = mentionNormalized.multiply(BigDecimal.valueOf(20));

            BigDecimal themeScore = (asset.getTheme() == null || asset.getTheme().isBlank())
                    ? BigDecimal.valueOf(2)
                    : BigDecimal.valueOf(10);
            BigDecimal priorityThemeBonus = isPriorityTheme(themeCode) ? BigDecimal.valueOf(12) : BigDecimal.ZERO;

            long exposure = exposureCountByAsset.getOrDefault(asset.getAssetCode(), 0L);
            BigDecimal repeatPenalty = repeatPenaltyPerExposure
                    .multiply(BigDecimal.valueOf(exposure))
                    .setScale(2, RoundingMode.HALF_UP);

            String qualityScopeKey = scopeKey(country, asset.getTheme());
            BigDecimal qualityScore = latestQualityScoreByScope.getOrDefault(qualityScopeKey, BigDecimal.valueOf(100));
            BigDecimal qualityPenalty = qualityScore.compareTo(minQualityScoreForCore) < 0
                    ? BigDecimal.valueOf(25)
                    : BigDecimal.ZERO;
            boolean tradeEligibleByQuality = qualityScore.compareTo(minQualityScoreForCore) >= 0;

            BigDecimal raw = liquidityScore
                    .add(volumeScore)
                    .add(volatilityScore)
                    .add(mentionScore)
                    .add(themeScore)
                    .add(priorityThemeBonus)
                    .subtract(repeatPenalty)
                    .subtract(qualityPenalty);
            BigDecimal finalScore = raw.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal diversityScore = calculateBaseDiversityScore(asset, mentionNormalized, exposure, themeCode, tradeEligibleByQuality);

            AssetSelectionSourceType selectionSource = decideSelectionSource(asset, mentionNormalized, volumeNormalized);
            asset.setMarketCapRank(marketCapRankByAsset.getOrDefault(asset.getAssetCode(), null));
            String selectionReason = buildSelectionReason(
                    asset,
                    themeCode,
                    selectionSource,
                    finalScore,
                    qualityScore,
                    repeatPenalty,
                    priorityThemeBonus,
                    mentionNormalized,
                    volumeNormalized,
                    volatilityNormalized,
                    tradeEligibleByQuality);
            ranked.add(new AssetRank(
                    asset,
                    finalScore,
                    qualityScore,
                    repeatPenalty,
                    selectionSource,
                    diversityScore,
                    tradeEligibleByQuality,
                    isPriorityTheme(themeCode),
                    selectionReason,
                    mentionNormalized,
                    volumeNormalized,
                    volatilityNormalized));
        }

        ranked.sort(Comparator.comparing(AssetRank::score).reversed()
                .thenComparing(a -> safeDecimal(a.asset().getLiquidityScore()), Comparator.reverseOrder())
                .thenComparing(a -> a.asset().getAssetCode()));
        return ranked;
    }

    private List<AssetUniverseEntity> selectCoreAssetsWithDiversity(List<AssetRank> ranked) {
        int target = Math.max(1, coreAssetsPerCountry);
        Set<String> selectedCodes = new HashSet<>();
        Map<String, Integer> familyCount = new HashMap<>();
        Map<String, Integer> typeCount = new HashMap<>();
        List<AssetUniverseEntity> selected = new ArrayList<>();

        for (AssetRank rank : ranked) {
            if (selected.size() >= target) {
                break;
            }
            AssetUniverseEntity asset = rank.asset();
            String family = tickerFamily(asset.getAssetCode());
            String type = asset.getAssetType() == null ? "UNKNOWN" : asset.getAssetType().name();
            boolean qualityAllowed = rank.qualityScore().compareTo(minQualityScoreForCore) >= 0;
            boolean tradeAllowed = rank.tradeEligible();
            boolean familyAllowed = familyCount.getOrDefault(family, 0) < Math.max(1, maxSameFamilyExposure);
            boolean typeAllowed = typeCount.getOrDefault(type, 0) < Math.max(1, target / 2 + 1);

            if (qualityAllowed && tradeAllowed && familyAllowed && typeAllowed) {
                selected.add(asset);
                selectedCodes.add(asset.getAssetCode());
                familyCount.merge(family, 1, Integer::sum);
                typeCount.merge(type, 1, Integer::sum);
            }
        }

        // 다양성 규칙으로 target 미달이면 품질 하한만 유지한 채 보충한다.
        if (selected.size() < target) {
            for (AssetRank rank : ranked) {
                if (selected.size() >= target) {
                    break;
                }
                AssetUniverseEntity asset = rank.asset();
                if (selectedCodes.contains(asset.getAssetCode())) {
                    continue;
                }
                if (rank.qualityScore().compareTo(minQualityScoreForCore) < 0 || !rank.tradeEligible()) {
                    continue;
                }
                selected.add(asset);
                selectedCodes.add(asset.getAssetCode());
            }
        }

        // 품질 하한으로도 부족하면 최소 수 보장을 위해 fallback 포함
        if (selected.size() < target) {
            for (AssetRank rank : ranked) {
                if (selected.size() >= target) {
                    break;
                }
                AssetUniverseEntity asset = rank.asset();
                if (selectedCodes.contains(asset.getAssetCode())) {
                    continue;
                }
                selected.add(asset);
                selectedCodes.add(asset.getAssetCode());
            }
        }

        selected.forEach(asset -> asset.setIsCoreAsset(true));
        return selected;
    }

    private int ensureCountryMinimum(List<AssetUniverseEntity> countryAssets, List<AssetUniverseEntity> selectedCore) {
        int minCount = Math.max(1, minAssetsPerCountry);
        if (selectedCore.size() >= minCount) {
            return 0;
        }
        int before = selectedCore.size();
        List<AssetUniverseEntity> sorted = countryAssets.stream()
                .sorted(Comparator.comparing(AssetUniverseEntity::getSelectionScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AssetUniverseEntity::getAssetCode))
                .toList();
        Set<String> selectedCodes = selectedCore.stream().map(AssetUniverseEntity::getAssetCode).collect(Collectors.toSet());
        for (AssetUniverseEntity asset : sorted) {
            if (selectedCore.size() >= minCount) {
                break;
            }
            if (selectedCodes.add(asset.getAssetCode())) {
                selectedCore.add(asset);
                asset.setIsCoreAsset(true);
            }
        }
        return Math.max(0, selectedCore.size() - before);
    }

    private int ensureThemeMinimum(List<AssetUniverseEntity> countryAssets, List<AssetUniverseEntity> selectedCore) {
        int minCount = Math.max(1, minAssetsPerTheme);
        Map<String, List<AssetUniverseEntity>> byTheme = countryAssets.stream()
                .collect(Collectors.groupingBy(asset -> safeString(asset.getTheme(), "N/A")));
        int fallbackCount = 0;
        Set<String> selectedCodes = selectedCore.stream().map(AssetUniverseEntity::getAssetCode).collect(Collectors.toSet());

        for (Map.Entry<String, List<AssetUniverseEntity>> themeEntry : byTheme.entrySet()) {
            String theme = themeEntry.getKey();
            List<AssetUniverseEntity> themeAssets = themeEntry.getValue().stream()
                    .sorted(Comparator.comparing(AssetUniverseEntity::getSelectionScore, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            long already = selectedCore.stream()
                    .filter(asset -> theme.equals(safeString(asset.getTheme(), "N/A")))
                    .count();
            if (already >= minCount) {
                continue;
            }
            for (AssetUniverseEntity asset : themeAssets) {
                if (already >= minCount) {
                    break;
                }
                if (selectedCodes.add(asset.getAssetCode())) {
                    selectedCore.add(asset);
                    asset.setIsCoreAsset(true);
                    already++;
                    fallbackCount++;
                }
            }
        }
        return fallbackCount;
    }

    private void assignUniverseLayers(
            List<AssetUniverseEntity> countryAssets,
            List<AssetRank> ranked,
            List<AssetUniverseEntity> selectedCore) {
        Set<String> coreCodes = selectedCore.stream().map(AssetUniverseEntity::getAssetCode).collect(Collectors.toSet());
        Set<String> assignedThemeLeaders = new HashSet<>();

        for (AssetUniverseEntity asset : countryAssets) {
            if (coreCodes.contains(asset.getAssetCode())) {
                asset.setUniverseLayer(UniverseLayerType.CORE);
                asset.setIsCoreAsset(true);
                continue;
            }
            asset.setIsCoreAsset(false);
            if (Boolean.TRUE.equals(asset.getIsUserWatch()) || Boolean.TRUE.equals(asset.getIsWatchlistAsset())) {
                asset.setUniverseLayer(UniverseLayerType.WATCHLIST);
            } else {
                asset.setUniverseLayer(UniverseLayerType.DISCOVERY);
            }
        }

        Map<String, Integer> leadersPerTheme = new HashMap<>();
        for (AssetRank rank : ranked) {
            AssetUniverseEntity asset = rank.asset();
            if (coreCodes.contains(asset.getAssetCode())) {
                continue;
            }
            if (!rank.tradeEligible()) {
                continue;
            }
            String themeCode = normalizeThemeCode(asset.getTheme());
            if (!isPriorityTheme(themeCode)) {
                continue;
            }
            int current = leadersPerTheme.getOrDefault(themeCode, 0);
            if (current >= Math.max(1, themeLeaderPerPriorityTheme)) {
                continue;
            }
            if (asset.getUniverseLayer() == UniverseLayerType.WATCHLIST) {
                continue;
            }
            asset.setUniverseLayer(UniverseLayerType.THEME_LEADER);
            leadersPerTheme.put(themeCode, current + 1);
            assignedThemeLeaders.add(asset.getAssetCode());
        }

        // 최소한 일부 발굴 레이어가 유지되도록 상위 점수 기반으로 DISCOVERY를 재보장한다.
        int discoveryBudget = Math.max(2, Math.min(8, countryAssets.size() / 4));
        int currentDiscovery = (int) countryAssets.stream().filter(a -> a.getUniverseLayer() == UniverseLayerType.DISCOVERY).count();
        if (currentDiscovery < discoveryBudget) {
            for (AssetRank rank : ranked) {
                if (currentDiscovery >= discoveryBudget) {
                    break;
                }
                AssetUniverseEntity asset = rank.asset();
                if (coreCodes.contains(asset.getAssetCode())) {
                    continue;
                }
                if (!rank.tradeEligible()) {
                    continue;
                }
                if (asset.getUniverseLayer() == UniverseLayerType.DISCOVERY) {
                    currentDiscovery++;
                    continue;
                }
                if (asset.getUniverseLayer() == UniverseLayerType.WATCHLIST) {
                    continue;
                }
                if (assignedThemeLeaders.contains(asset.getAssetCode())) {
                    asset.setUniverseLayer(UniverseLayerType.DISCOVERY);
                    currentDiscovery++;
                }
            }
        }
    }

    private void assignDisplayWeights(List<AssetUniverseEntity> countryAssets) {
        List<AssetUniverseEntity> sorted = countryAssets.stream()
                .sorted(Comparator.comparing(
                                (AssetUniverseEntity asset) -> layerBaseWeight(asset.getUniverseLayer()))
                        .reversed()
                        .thenComparing(AssetUniverseEntity::getSelectionScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AssetUniverseEntity::getDiversityScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AssetUniverseEntity::getAssetCode))
                .toList();

        for (int i = 0; i < sorted.size(); i++) {
            AssetUniverseEntity asset = sorted.get(i);
            int rankPenalty = i * 2;
            int base = layerBaseWeight(asset.getUniverseLayer());
            int scoreBonus = safeDecimal(asset.getSelectionScore()).intValue();
            int diversityBonus = safeDecimal(asset.getDiversityScore()).multiply(BigDecimal.valueOf(10)).intValue();
            asset.setDisplayWeight(Math.max(0, base + scoreBonus + diversityBonus - rankPenalty));
            if (asset.getVerificationStatus() == null) {
                asset.setVerificationStatus(AssetVerificationStatusType.UNVERIFIED);
            }
        }
    }

    private Map<String, BigDecimal> calculateAverageVolumeByAsset(List<AssetUniverseEntity> assets) {
        Map<String, BigDecimal> result = new HashMap<>();
        for (AssetUniverseEntity asset : assets) {
            List<MarketPriceBarEntity> bars = marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(
                    asset.getAssetCode(), "D1");
            BigDecimal barAvgVolume = averageVolume(
                    bars.stream().limit(60).map(MarketPriceBarEntity::getVolume).toList());

            List<MarketQuoteSnapshotEntity> quotes = marketQuoteSnapshotRepository.findTop2ByAssetCodeOrderBySnapshotUtcDesc(
                    asset.getAssetCode());
            BigDecimal quoteVolume = averageVolume(quotes.stream().map(MarketQuoteSnapshotEntity::getVolume).toList());

            BigDecimal merged = barAvgVolume.max(quoteVolume).setScale(4, RoundingMode.HALF_UP);
            result.put(asset.getAssetCode(), merged);
        }
        return result;
    }

    private Map<String, BigDecimal> calculateVolatilityByAsset(List<AssetUniverseEntity> assets) {
        Map<String, BigDecimal> result = new HashMap<>();
        int lookback = Math.max(5, volatilityLookbackBars);
        BigDecimal maxRangePct = safeDecimal(volatilityMaxRangePct).compareTo(BigDecimal.ZERO) > 0
                ? safeDecimal(volatilityMaxRangePct)
                : BigDecimal.valueOf(0.20d);
        for (AssetUniverseEntity asset : assets) {
            List<MarketPriceBarEntity> bars = marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(
                    asset.getAssetCode(), "D1");
            List<MarketPriceBarEntity> sample = bars.stream().limit(lookback).toList();
            if (sample.isEmpty()) {
                result.put(asset.getAssetCode(), BigDecimal.ZERO);
                continue;
            }
            BigDecimal avgRangePct = sample.stream()
                    .map(this::barRangePct)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(sample.size()), 6, RoundingMode.HALF_UP);
            BigDecimal normalized = maxRangePct.compareTo(BigDecimal.ZERO) <= 0
                    ? BigDecimal.ZERO
                    : avgRangePct.divide(maxRangePct, 6, RoundingMode.HALF_UP);
            result.put(asset.getAssetCode(), clamp01(normalized));
        }
        return result;
    }

    private Map<String, OffsetDateTime> resolveLatestQuoteReceivedAtByAsset(List<AssetUniverseEntity> assets) {
        Map<String, OffsetDateTime> result = new HashMap<>();
        for (AssetUniverseEntity asset : assets) {
            marketQuoteSnapshotRepository.findTop1ByAssetCodeOrderBySnapshotUtcDesc(asset.getAssetCode())
                    .ifPresent(quote -> result.put(asset.getAssetCode(),
                            quote.getQuoteTimeUtc() != null ? quote.getQuoteTimeUtc() : quote.getSnapshotUtc()));
        }
        return result;
    }

    private Map<String, OffsetDateTime> resolveLatestSignalGeneratedAtByAsset(List<AssetUniverseEntity> assets) {
        Map<String, OffsetDateTime> result = new HashMap<>();
        for (AssetUniverseEntity asset : assets) {
            tradingSignalRepository.findTop1ByAssetCodeOrderByGeneratedAtDesc(asset.getAssetCode())
                    .ifPresent(signal -> result.put(asset.getAssetCode(), signal.getGeneratedAt()));
        }
        return result;
    }

    private Map<String, OffsetDateTime> resolveLatestNewsLinkedAtByAsset(List<AssetUniverseEntity> assets) {
        Map<String, OffsetDateTime> result = new HashMap<>();
        for (AssetUniverseEntity asset : assets) {
            newsAssetLinkRepository.findTop1ByAssetCodeOrderByCreatedAtDesc(asset.getAssetCode())
                    .ifPresent(link -> result.put(asset.getAssetCode(), link.getCreatedAt()));
        }
        return result;
    }

    private BigDecimal averageVolume(List<BigDecimal> volumes) {
        List<BigDecimal> filtered = volumes.stream().filter(v -> v != null && v.compareTo(BigDecimal.ZERO) > 0).toList();
        if (filtered.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = filtered.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(filtered.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal barRangePct(MarketPriceBarEntity bar) {
        if (bar == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal high = safeDecimal(bar.getHighPrice());
        BigDecimal low = safeDecimal(bar.getLowPrice());
        BigDecimal base = safeDecimal(bar.getClosePrice());
        if (base.compareTo(BigDecimal.ZERO) <= 0) {
            base = safeDecimal(bar.getOpenPrice());
        }
        if (base.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return high.subtract(low).max(BigDecimal.ZERO)
                .divide(base, 6, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO);
    }

    private AssetSelectionSourceType decideSelectionSource(
            AssetUniverseEntity asset,
            BigDecimal mentionNormalized,
            BigDecimal volumeNormalized) {
        if (Boolean.TRUE.equals(asset.getIsUserWatch()) || Boolean.TRUE.equals(asset.getIsWatchlistAsset())) {
            return AssetSelectionSourceType.WATCHLIST;
        }
        if (mentionNormalized.compareTo(BigDecimal.valueOf(0.7d)) >= 0) {
            return AssetSelectionSourceType.THEME_LEADER;
        }
        if (volumeNormalized.compareTo(BigDecimal.valueOf(0.7d)) >= 0) {
            return AssetSelectionSourceType.VOLUME;
        }
        if (safeString(asset.getTheme(), "").toLowerCase().contains("discovery")) {
            return AssetSelectionSourceType.DISCOVERY;
        }
        return AssetSelectionSourceType.MARKET_CAP;
    }

    private String resolveStrategyScope(AssetRank rank) {
        if (rank == null || rank.asset() == null) {
            return "ALL";
        }
        AssetUniverseEntity asset = rank.asset();
        if (!rank.tradeEligible()) {
            return "DISCOVERY";
        }
        if (Boolean.TRUE.equals(asset.getIsUserWatch()) || Boolean.TRUE.equals(asset.getIsWatchlistAsset())) {
            return "ALL";
        }
        if (rank.priorityTheme() && rank.mentionNormalized().compareTo(BigDecimal.valueOf(0.55d)) >= 0) {
            return "SCALP_SWING";
        }
        if (rank.volatilityNormalized().compareTo(BigDecimal.valueOf(0.70d)) >= 0
                && rank.volumeNormalized().compareTo(BigDecimal.valueOf(0.45d)) >= 0) {
            return "SCALP";
        }
        if (rank.volatilityNormalized().compareTo(BigDecimal.valueOf(0.25d)) <= 0
                && rank.qualityScore().compareTo(minQualityScoreForCore) >= 0) {
            return "SWING";
        }
        if (asset.getUniverseLayer() == UniverseLayerType.DISCOVERY) {
            return "DISCOVERY";
        }
        return "ALL";
    }

    private void normalizePanelExposureWindow(AssetUniverseEntity asset, OffsetDateTime now) {
        if (asset == null) {
            return;
        }
        if (asset.getPanelExposureCount24h() == null) {
            asset.setPanelExposureCount24h(0);
        }
        OffsetDateTime lastExposed = asset.getLastPanelExposedAt();
        if (lastExposed != null && lastExposed.isBefore(now.minusHours(24))) {
            asset.setPanelExposureCount24h(0);
        }
    }

    private String normalizeCountryCode(String country) {
        if (country == null || country.isBlank()) {
            return null;
        }
        return country.trim().toUpperCase(Locale.ROOT);
    }

    private List<AssetUniverseEntity> resolveByCountryAndThemeOrThemeCode(String country, String theme) {
        String normalizedThemeCode = normalizeThemeCode(theme);
        List<AssetUniverseEntity> byRawTheme = assetUniverseRepository.findByCountryAndThemeOrderBySelectionScoreDesc(country, theme);
        if (!byRawTheme.isEmpty()) {
            return byRawTheme;
        }
        List<AssetUniverseEntity> byThemeCode = assetUniverseRepository
                .findByCountryAndThemeCodeAndActiveTrueOrderByDisplayWeightDescSelectionScoreDescUpdatedAtDesc(country, normalizedThemeCode);
        if (!byThemeCode.isEmpty()) {
            return byThemeCode;
        }
        return assetUniverseRepository.findTop200ByCountryAndThemeCodeAndActiveTrueOrderByUpdatedAtDesc(country, normalizedThemeCode);
    }

    private Map<String, BigDecimal> resolveLatestQualityScores() {
        List<MarketDataQualitySnapshotEntity> snapshots = marketDataQualitySnapshotRepository.findTop200ByOrderBySnapshotTimeUtcDesc();
        Map<String, BigDecimal> map = new HashMap<>();
        for (MarketDataQualitySnapshotEntity snapshot : snapshots) {
            String key = scopeKey(snapshot.getCountry(), snapshot.getTheme());
            map.putIfAbsent(key, safeDecimal(snapshot.getQualityScore()));
            String themeCodeKey = scopeKey(snapshot.getCountry(), normalizeThemeCode(snapshot.getTheme()));
            map.putIfAbsent(themeCodeKey, safeDecimal(snapshot.getQualityScore()));
        }
        return map;
    }

    private String scopeKey(String country, String theme) {
        return safeString(country, "ALL") + "::" + safeString(theme, "N/A");
    }

    private BigDecimal calculateBaseDiversityScore(
            AssetUniverseEntity asset,
            BigDecimal mentionNormalized,
            long exposureCount,
            String themeCode,
            boolean tradeEligibleByQuality) {
        BigDecimal score = BigDecimal.ONE;
        if (Boolean.TRUE.equals(asset.getIsUserWatch()) || Boolean.TRUE.equals(asset.getIsWatchlistAsset())) {
            score = score.subtract(BigDecimal.valueOf(0.05d));
        }
        if (isPriorityTheme(themeCode)) {
            score = score.subtract(BigDecimal.valueOf(0.08d));
        }
        if (mentionNormalized.compareTo(BigDecimal.valueOf(0.8d)) > 0) {
            score = score.subtract(BigDecimal.valueOf(0.10d));
        }
        if (exposureCount > 0) {
            score = score.subtract(BigDecimal.valueOf(Math.min(0.40d, exposureCount * 0.05d)));
        }
        if (!tradeEligibleByQuality) {
            score = score.subtract(BigDecimal.valueOf(0.20d));
        }
        return score.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private String buildSelectionReason(
            AssetUniverseEntity asset,
            String themeCode,
            AssetSelectionSourceType selectionSource,
            BigDecimal finalScore,
            BigDecimal qualityScore,
            BigDecimal repeatPenalty,
            BigDecimal priorityThemeBonus,
            BigDecimal mentionNormalized,
            BigDecimal volumeNormalized,
            BigDecimal volatilityNormalized,
            boolean tradeEligibleByQuality) {
        StringBuilder sb = new StringBuilder();
        sb.append("source=").append(selectionSource == null ? "MANUAL" : selectionSource.name());
        sb.append(",score=").append(safeDecimal(finalScore).setScale(2, RoundingMode.HALF_UP));
        sb.append(",quality=").append(safeDecimal(qualityScore).setScale(2, RoundingMode.HALF_UP));
        if (isPriorityTheme(themeCode)) {
            sb.append(",priority_theme=").append(themeCode);
        }
        if (priorityThemeBonus.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(",priority_bonus=").append(priorityThemeBonus.setScale(2, RoundingMode.HALF_UP));
        }
        if (repeatPenalty.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(",repeat_penalty=").append(repeatPenalty.setScale(2, RoundingMode.HALF_UP));
        }
        sb.append(",mention_norm=").append(mentionNormalized.setScale(2, RoundingMode.HALF_UP));
        sb.append(",volume_norm=").append(volumeNormalized.setScale(2, RoundingMode.HALF_UP));
        sb.append(",volatility_norm=").append(safeDecimal(volatilityNormalized).setScale(2, RoundingMode.HALF_UP));
        sb.append(",trade_quality_ok=").append(tradeEligibleByQuality);
        if (asset.getTheme() != null && !asset.getTheme().isBlank()) {
            sb.append(",raw_theme=").append(normalizeTextForReason(asset.getTheme()));
        }
        return sb.toString();
    }

    private int resolveDupExposureCooldownMinutes(AssetRank rank) {
        int base = Math.max(0, defaultDupExposureCooldownMinutes);
        if (rank.priorityTheme()) {
            base += 15;
        }
        if (rank.repeatPenalty().compareTo(BigDecimal.valueOf(3)) >= 0) {
            base += 30;
        }
        return base;
    }

    private boolean isTradeEligible(AssetUniverseEntity asset, BigDecimal qualityScore, OffsetDateTime lastQuoteReceivedAt) {
        if (asset == null || !Boolean.TRUE.equals(asset.getActive())) {
            return false;
        }
        if (asset.getVerificationStatus() == AssetVerificationStatusType.FAILED) {
            return false;
        }
        if (qualityScore != null && qualityScore.compareTo(minQualityScoreForCore) < 0) {
            return false;
        }
        return hasFreshQuote(lastQuoteReceivedAt);
    }

    private boolean hasFreshQuote(OffsetDateTime lastQuoteReceivedAt) {
        if (lastQuoteReceivedAt == null) {
            return false;
        }
        return lastQuoteReceivedAt.isAfter(OffsetDateTime.now().minusMinutes(Math.max(1, quoteFreshnessThresholdMinutes)));
    }

    private boolean hasActivePanelCooldown(AssetUniverseEntity asset) {
        if (asset == null || asset.getLastPanelExposedAt() == null) {
            return false;
        }
        int cooldownMinutes = asset.getDupExposureCooldownMinutes() == null ? 0 : Math.max(0, asset.getDupExposureCooldownMinutes());
        if (cooldownMinutes <= 0) {
            return false;
        }
        return asset.getLastPanelExposedAt().isAfter(OffsetDateTime.now().minusMinutes(cooldownMinutes));
    }

    private boolean hasFreshSignal(OffsetDateTime lastSignalGeneratedAt) {
        if (lastSignalGeneratedAt == null) {
            return false;
        }
        return lastSignalGeneratedAt.isAfter(OffsetDateTime.now().minusHours(Math.max(1, signalFreshnessThresholdHours)));
    }

    private boolean hasFreshNewsLink(OffsetDateTime lastNewsLinkedAt) {
        if (lastNewsLinkedAt == null) {
            return false;
        }
        return lastNewsLinkedAt.isAfter(OffsetDateTime.now().minusHours(Math.max(1, newsLinkFreshnessThresholdHours)));
    }

    private int layerBaseWeight(UniverseLayerType layer) {
        if (layer == null) {
            return 100;
        }
        return switch (layer) {
            case CORE -> 300;
            case WATCHLIST -> 260;
            case THEME_LEADER -> 220;
            case DISCOVERY -> 160;
        };
    }

    private boolean isPriorityTheme(String themeCode) {
        if (themeCode == null || themeCode.isBlank()) {
            return false;
        }
        return normalizedPriorityThemes().contains(themeCode);
    }

    private Set<String> normalizedPriorityThemes() {
        if (priorityThemes == null || priorityThemes.isEmpty()) {
            return Set.of("RESOURCE", "DEFENSE", "SPACE", "AI", "SEMICONDUCTOR", "ROBOTICS", "ENERGY");
        }
        return priorityThemes.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(this::normalizeThemeCode)
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
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

    private String normalizeTextForReason(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replaceAll("[\\r\\n]+", " ").trim();
        return v.length() > 64 ? v.substring(0, 64) : v;
    }

    private BigDecimal clamp01(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private BigDecimal safeDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String tickerFamily(String assetCode) {
        if (assetCode == null || assetCode.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = assetCode.toUpperCase();
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

    private String traceId() {
        String trace = MDC.get("trace_id");
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }

    private record AssetRank(
            AssetUniverseEntity asset,
            BigDecimal score,
            BigDecimal qualityScore,
            BigDecimal repeatPenalty,
            AssetSelectionSourceType selectionSource,
            BigDecimal diversityScore,
            boolean tradeEligible,
            boolean priorityTheme,
            String selectionReason,
            BigDecimal mentionNormalized,
            BigDecimal volumeNormalized,
            BigDecimal volatilityNormalized) {
    }

    public record UniverseRebuildResult(
            int totalAssets,
            int coreAssets,
            int countryMinimumFallbackCount,
            int themeMinimumFallbackCount,
            String traceId,
            String triggeredBy,
            OffsetDateTime generatedAt) {
    }
}

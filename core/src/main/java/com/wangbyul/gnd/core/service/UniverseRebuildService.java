package com.wangbyul.gnd.core.service;

import com.wangbyul.gnd.core.domain.AssetSelectionSourceType;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.AssetVerificationStatusType;
import com.wangbyul.gnd.core.domain.MarketDataQualitySnapshotEntity;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
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
import java.util.List;
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
        Map<String, Long> exposureCountByAsset = new HashMap<>();
        for (AssetUniverseEntity asset : assets) {
            exposureCountByAsset.put(
                    asset.getAssetCode(),
                    tradingSignalRepository.countByAssetCodeAndGeneratedAtAfter(asset.getAssetCode(), exposureSince));
        }

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

            assignDisplayWeights(selectedCore);
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
            filtered = assetUniverseRepository.findByCountryAndThemeOrderBySelectionScoreDesc(country, theme);
        } else if (country != null && !country.isBlank()) {
            filtered = assetUniverseRepository.findByCountryOrderBySelectionScoreDesc(country);
        } else {
            filtered = assetUniverseRepository.findByActiveTrueOrderByUpdatedAtDesc();
            filtered.sort(Comparator.comparing(
                            AssetUniverseEntity::getSelectionScore,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(AssetUniverseEntity::getDisplayWeight, Comparator.nullsLast(Comparator.reverseOrder())));
        }

        List<Map<String, Object>> topAssets = filtered.stream()
                .limit(Math.max(1, limit))
                .map(asset -> Map.<String, Object>of(
                        "asset_code", asset.getAssetCode(),
                        "asset_name", asset.getAssetName(),
                        "country", asset.getCountry(),
                        "theme", safeString(asset.getTheme(), "N/A"),
                        "selection_source", safeString(asset.getSelectionSource() == null ? null : asset.getSelectionSource().name(), "MANUAL"),
                        "selection_score", safeDecimal(asset.getSelectionScore()),
                        "display_weight", asset.getDisplayWeight() == null ? 0 : asset.getDisplayWeight(),
                        "is_core_asset", Boolean.TRUE.equals(asset.getIsCoreAsset()),
                        "verification_status", safeString(asset.getVerificationStatus() == null ? null : asset.getVerificationStatus().name(), "UNVERIFIED")))
                .toList();

        Map<String, Long> byCountry = filtered.stream()
                .collect(Collectors.groupingBy(AssetUniverseEntity::getCountry, Collectors.counting()));
        Map<String, Long> byTheme = filtered.stream()
                .collect(Collectors.groupingBy(
                        asset -> safeString(asset.getTheme(), "N/A"),
                        Collectors.counting()));

        Map<String, Long> topFamilies = filtered.stream()
                .collect(Collectors.groupingBy(asset -> tickerFamily(asset.getAssetCode()), Collectors.counting()))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, java.util.LinkedHashMap::new));

        long coreCount = filtered.stream().filter(asset -> Boolean.TRUE.equals(asset.getIsCoreAsset())).count();
        long watchlistCount = filtered.stream().filter(asset -> Boolean.TRUE.equals(asset.getIsWatchlistAsset())).count();

        return Map.of(
                "scope_country", safeString(country, "ALL"),
                "scope_theme", safeString(theme, "ALL"),
                "total_assets", filtered.size(),
                "core_assets", coreCount,
                "watchlist_assets", watchlistCount,
                "assets_by_country", byCountry,
                "assets_by_theme", byTheme,
                "top_repeated_families", topFamilies,
                "minimum_rules", Map.of(
                        "min_assets_per_country", Math.max(1, minAssetsPerCountry),
                        "min_assets_per_theme", Math.max(1, minAssetsPerTheme),
                        "max_same_family_exposure", Math.max(1, maxSameFamilyExposure)),
                "top_assets", topAssets);
    }

    private List<AssetRank> rankAssets(
            List<AssetUniverseEntity> assets,
            Map<String, Long> mentionCountByAsset,
            Map<String, BigDecimal> avgVolumeByAsset,
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
            BigDecimal liquidity = safeDecimal(asset.getLiquidityScore());
            BigDecimal liquidityScore = clamp01(liquidity).multiply(BigDecimal.valueOf(30));

            BigDecimal avgVolume = avgVolumeByAsset.getOrDefault(asset.getAssetCode(), BigDecimal.ZERO);
            BigDecimal volumeNormalized = maxVolume.compareTo(BigDecimal.ZERO) <= 0
                    ? BigDecimal.ZERO
                    : avgVolume.divide(maxVolume, 6, RoundingMode.HALF_UP).min(BigDecimal.ONE);
            BigDecimal volumeScore = volumeNormalized.multiply(BigDecimal.valueOf(35));

            long mentions = mentionCountByAsset.getOrDefault(asset.getAssetCode(), 0L);
            BigDecimal mentionNormalized = BigDecimal.valueOf(mentions)
                    .divide(BigDecimal.valueOf(Math.max(1L, maxMentions)), 6, RoundingMode.HALF_UP);
            BigDecimal mentionScore = mentionNormalized.multiply(BigDecimal.valueOf(20));

            BigDecimal themeScore = (asset.getTheme() == null || asset.getTheme().isBlank())
                    ? BigDecimal.valueOf(2)
                    : BigDecimal.valueOf(10);

            long exposure = exposureCountByAsset.getOrDefault(asset.getAssetCode(), 0L);
            BigDecimal repeatPenalty = repeatPenaltyPerExposure
                    .multiply(BigDecimal.valueOf(exposure))
                    .setScale(2, RoundingMode.HALF_UP);

            String qualityScopeKey = scopeKey(country, asset.getTheme());
            BigDecimal qualityScore = latestQualityScoreByScope.getOrDefault(qualityScopeKey, BigDecimal.valueOf(100));
            BigDecimal qualityPenalty = qualityScore.compareTo(minQualityScoreForCore) < 0
                    ? BigDecimal.valueOf(25)
                    : BigDecimal.ZERO;

            BigDecimal raw = liquidityScore
                    .add(volumeScore)
                    .add(mentionScore)
                    .add(themeScore)
                    .subtract(repeatPenalty)
                    .subtract(qualityPenalty);
            BigDecimal finalScore = raw.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

            AssetSelectionSourceType selectionSource = decideSelectionSource(asset, mentionNormalized, volumeNormalized);
            asset.setMarketCapRank(marketCapRankByAsset.getOrDefault(asset.getAssetCode(), null));
            ranked.add(new AssetRank(asset, finalScore, qualityScore, repeatPenalty, selectionSource));
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
            boolean familyAllowed = familyCount.getOrDefault(family, 0) < Math.max(1, maxSameFamilyExposure);
            boolean typeAllowed = typeCount.getOrDefault(type, 0) < Math.max(1, target / 2 + 1);

            if (qualityAllowed && familyAllowed && typeAllowed) {
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
                if (rank.qualityScore().compareTo(minQualityScoreForCore) < 0) {
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

    private void assignDisplayWeights(List<AssetUniverseEntity> selectedCore) {
        selectedCore.sort(Comparator.comparing(AssetUniverseEntity::getSelectionScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AssetUniverseEntity::getAssetCode));
        for (int i = 0; i < selectedCore.size(); i++) {
            AssetUniverseEntity asset = selectedCore.get(i);
            asset.setDisplayWeight(Math.max(1, 100 - (i * 3)));
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

    private BigDecimal averageVolume(List<BigDecimal> volumes) {
        List<BigDecimal> filtered = volumes.stream().filter(v -> v != null && v.compareTo(BigDecimal.ZERO) > 0).toList();
        if (filtered.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = filtered.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(filtered.size()), 6, RoundingMode.HALF_UP);
    }

    private AssetSelectionSourceType decideSelectionSource(
            AssetUniverseEntity asset,
            BigDecimal mentionNormalized,
            BigDecimal volumeNormalized) {
        if (Boolean.TRUE.equals(asset.getIsWatchlistAsset())) {
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

    private Map<String, BigDecimal> resolveLatestQualityScores() {
        List<MarketDataQualitySnapshotEntity> snapshots = marketDataQualitySnapshotRepository.findTop200ByOrderBySnapshotTimeUtcDesc();
        Map<String, BigDecimal> map = new HashMap<>();
        for (MarketDataQualitySnapshotEntity snapshot : snapshots) {
            String key = scopeKey(snapshot.getCountry(), snapshot.getTheme());
            map.putIfAbsent(key, safeDecimal(snapshot.getQualityScore()));
        }
        return map;
    }

    private String scopeKey(String country, String theme) {
        return safeString(country, "ALL") + "::" + safeString(theme, "N/A");
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
            AssetSelectionSourceType selectionSource) {
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

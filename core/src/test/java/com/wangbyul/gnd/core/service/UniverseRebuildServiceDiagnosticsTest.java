package com.wangbyul.gnd.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.wangbyul.gnd.core.domain.AssetSelectionSourceType;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.AssetVerificationStatusType;
import com.wangbyul.gnd.core.domain.UniverseLayerType;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketDataQualitySnapshotRepository;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.NewsAssetLinkRepository;
import com.wangbyul.gnd.core.repository.TradingSignalRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 유니버스 진단 응답 구조(레이어/테마코드/신선도 메타) 검증.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@ExtendWith(MockitoExtension.class)
class UniverseRebuildServiceDiagnosticsTest {

    @Mock
    private AssetUniverseRepository assetUniverseRepository;
    @Mock
    private MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    @Mock
    private MarketPriceBarRepository marketPriceBarRepository;
    @Mock
    private MarketDataQualitySnapshotRepository marketDataQualitySnapshotRepository;
    @Mock
    private NewsAssetLinkRepository newsAssetLinkRepository;
    @Mock
    private TradingSignalRepository tradingSignalRepository;

    @Test
    @SuppressWarnings("unchecked")
    void universeDiagnosticsShouldExposeLayerThemeCodeAndFreshnessCounters() {
        UniverseRebuildService service = new UniverseRebuildService(
                assetUniverseRepository,
                marketQuoteSnapshotRepository,
                marketPriceBarRepository,
                marketDataQualitySnapshotRepository,
                newsAssetLinkRepository,
                tradingSignalRepository);
        ReflectionTestUtils.setField(service, "minAssetsPerCountry", 5);
        ReflectionTestUtils.setField(service, "minAssetsPerTheme", 3);
        ReflectionTestUtils.setField(service, "maxSameFamilyExposure", 2);
        ReflectionTestUtils.setField(service, "defaultDupExposureCooldownMinutes", 60);
        ReflectionTestUtils.setField(service, "quoteFreshnessThresholdMinutes", 180);
        ReflectionTestUtils.setField(service, "signalFreshnessThresholdHours", 48);
        ReflectionTestUtils.setField(service, "newsLinkFreshnessThresholdHours", 168);
        ReflectionTestUtils.setField(service, "priorityThemes", List.of("AI", "ENERGY"));

        OffsetDateTime now = OffsetDateTime.now();
        when(assetUniverseRepository.findByActiveTrueOrderByUpdatedAtDesc()).thenReturn(List.of(
                asset("AAA1", "Alpha AI", "KR", "인공지능", "AI", UniverseLayerType.CORE, true, true, now.minusMinutes(10)),
                asset("BBB1", "Beta Energy", "KR", "에너지", "ENERGY", UniverseLayerType.DISCOVERY, false, false, now.minusHours(6))));

        Map<String, Object> diagnostics = service.universeDiagnostics(null, null, 10);

        assertThat(diagnostics).containsKeys(
                "assets_by_layer",
                "assets_by_theme_code",
                "trade_enabled_assets",
                "stale_quote_assets",
                "priority_theme_assets",
                "top_assets");
        assertThat((Map<String, Long>) diagnostics.get("assets_by_layer"))
                .containsEntry("CORE", 1L)
                .containsEntry("DISCOVERY", 1L);
        assertThat((Map<String, Long>) diagnostics.get("assets_by_theme_code"))
                .containsEntry("AI", 1L)
                .containsEntry("ENERGY", 1L);
        assertThat(((Number) diagnostics.get("priority_theme_assets")).longValue()).isEqualTo(2L);
        assertThat(((Number) diagnostics.get("trade_enabled_assets")).longValue()).isEqualTo(1L);
        assertThat(((Number) diagnostics.get("stale_quote_assets")).longValue()).isGreaterThanOrEqualTo(1L);

        List<Map<String, Object>> topAssets = (List<Map<String, Object>>) diagnostics.get("top_assets");
        assertThat(topAssets).isNotEmpty();
        assertThat(topAssets.get(0)).containsKeys(
                "theme_code",
                "universe_layer",
                "selection_reason",
                "diversity_score",
                "is_trade_enabled",
                "dup_exposure_cooldown_minutes");
    }

    private AssetUniverseEntity asset(
            String code,
            String name,
            String country,
            String theme,
            String themeCode,
            UniverseLayerType layer,
            boolean tradeEnabled,
            boolean coreAsset,
            OffsetDateTime lastQuoteReceivedAt) {
        AssetUniverseEntity entity = new AssetUniverseEntity();
        entity.setAssetCode(code);
        entity.setAssetName(name);
        entity.setCountry(country);
        entity.setTheme(theme);
        entity.setThemeCode(themeCode);
        entity.setUniverseLayer(layer);
        entity.setSelectionSource(AssetSelectionSourceType.MANUAL);
        entity.setSelectionScore(BigDecimal.valueOf(80));
        entity.setDiversityScore(BigDecimal.valueOf(0.75d));
        entity.setSelectionReason("source=MANUAL,priority_theme=" + themeCode);
        entity.setDisplayWeight(500);
        entity.setActive(true);
        entity.setIsTradeEnabled(tradeEnabled);
        entity.setIsCoreAsset(coreAsset);
        entity.setIsUserWatch(false);
        entity.setIsWatchlistAsset(false);
        entity.setDupExposureCooldownMinutes(60);
        entity.setLastQuoteReceivedAt(lastQuoteReceivedAt);
        entity.setLastSignalGeneratedAt(OffsetDateTime.now().minusHours(1));
        entity.setLastNewsLinkedAt(OffsetDateTime.now().minusHours(2));
        entity.setVerificationStatus(AssetVerificationStatusType.VERIFIED);
        return entity;
    }
}

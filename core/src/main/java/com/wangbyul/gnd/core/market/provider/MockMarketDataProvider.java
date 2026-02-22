package com.wangbyul.gnd.core.market.provider;

import com.wangbyul.gnd.core.config.MarketProviderProperties;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.market.MarketDataProvider;
import com.wangbyul.gnd.core.market.dto.MarketAssetMetaDto;
import com.wangbyul.gnd.core.market.dto.MarketPriceBarDto;
import com.wangbyul.gnd.core.market.dto.MarketProviderFetchResult;
import com.wangbyul.gnd.core.market.dto.MarketProviderHealthDto;
import com.wangbyul.gnd.core.market.dto.MarketProviderHealthStatus;
import com.wangbyul.gnd.core.market.dto.MarketQuoteDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 외부 API 키 없이도 end-to-end 경로 검증이 가능한 Mock Provider.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Component
public class MockMarketDataProvider implements MarketDataProvider {

    private final MarketProviderProperties properties;

    public MockMarketDataProvider(MarketProviderProperties properties) {
        this.properties = properties;
    }

    @Override
    public String providerId() {
        return "mock";
    }

    @Override
    public MarketProviderFetchResult<MarketQuoteDto> fetchQuotes(List<AssetUniverseEntity> assets) {
        OffsetDateTime requestAt = OffsetDateTime.now();
        OffsetDateTime quoteTime = requestAt.truncatedTo(ChronoUnit.SECONDS);
        List<MarketQuoteDto> items = new ArrayList<>();
        for (AssetUniverseEntity asset : safeAssets(assets)) {
            BigDecimal lastPrice = basePrice(asset);
            BigDecimal direction = hashUnit(asset.getAssetCode()).compareTo(BigDecimal.valueOf(0.5d)) >= 0
                    ? BigDecimal.ONE
                    : BigDecimal.ONE.negate();
            BigDecimal changePct = hashUnit(asset.getAssetCode() + ":chg")
                    .multiply(BigDecimal.valueOf(3))
                    .multiply(direction)
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal spreadPct = BigDecimal.valueOf(0.05d)
                    .add(hashUnit(asset.getAssetCode() + ":spr").multiply(BigDecimal.valueOf(0.20d)))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal bidPrice = lastPrice.multiply(
                    BigDecimal.ONE.subtract(spreadPct.divide(BigDecimal.valueOf(200), 6, RoundingMode.HALF_UP)));
            BigDecimal askPrice = lastPrice.multiply(
                    BigDecimal.ONE.add(spreadPct.divide(BigDecimal.valueOf(200), 6, RoundingMode.HALF_UP)));
            BigDecimal bidSize = hashUnit(asset.getAssetCode() + ":bid-size")
                    .multiply(BigDecimal.valueOf(10000))
                    .add(BigDecimal.valueOf(100))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal askSize = hashUnit(asset.getAssetCode() + ":ask-size")
                    .multiply(BigDecimal.valueOf(10000))
                    .add(BigDecimal.valueOf(100))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal volume = hashUnit(asset.getAssetCode() + ":vol")
                    .multiply(BigDecimal.valueOf(1000000))
                    .add(BigDecimal.valueOf(1000))
                    .setScale(4, RoundingMode.HALF_UP);

            items.add(new MarketQuoteDto(
                    meta(asset),
                    quoteTime,
                    quoteTime,
                    lastPrice,
                    changePct,
                    bidPrice.setScale(6, RoundingMode.HALF_UP),
                    askPrice.setScale(6, RoundingMode.HALF_UP),
                    bidSize,
                    askSize,
                    volume,
                    providerId()));
        }
        OffsetDateTime responseAt = requestAt.plus(Math.max(0, properties.getMockLatencyMs()), ChronoUnit.MILLIS);
        Map<String, Object> meta = Map.of(
                "mock", true,
                "requested_assets", items.size(),
                "response_kind", "quote");
        return MarketProviderFetchResult.success(
                providerId(),
                "quotes",
                items,
                200,
                requestAt,
                responseAt,
                "{\"provider\":\"mock\",\"kind\":\"quotes\",\"record_count\":" + items.size() + "}",
                meta);
    }

    @Override
    public MarketProviderFetchResult<MarketPriceBarDto> fetchBars(List<AssetUniverseEntity> assets, String timeframe, int barsPerAsset) {
        OffsetDateTime requestAt = OffsetDateTime.now();
        int safeBarsPerAsset = Math.max(1, barsPerAsset);
        OffsetDateTime nowBucket = requestAt.truncatedTo(ChronoUnit.MINUTES);
        List<MarketPriceBarDto> items = new ArrayList<>();
        for (AssetUniverseEntity asset : safeAssets(assets)) {
            BigDecimal base = basePrice(asset);
            for (int i = 0; i < safeBarsPerAsset; i++) {
                OffsetDateTime barTime = nowBucket.minusMinutes(i);
                BigDecimal seed = hashUnit(asset.getAssetCode() + ":" + i);
                BigDecimal open = base.multiply(BigDecimal.ONE.add(seed.subtract(BigDecimal.valueOf(0.5d))
                        .divide(BigDecimal.valueOf(20), 6, RoundingMode.HALF_UP)));
                BigDecimal close = open.multiply(BigDecimal.ONE.add(hashUnit(asset.getAssetCode() + ":close:" + i)
                        .subtract(BigDecimal.valueOf(0.5d))
                        .divide(BigDecimal.valueOf(25), 6, RoundingMode.HALF_UP)));
                BigDecimal high = open.max(close).multiply(BigDecimal.valueOf(1.003d));
                BigDecimal low = open.min(close).multiply(BigDecimal.valueOf(0.997d));
                BigDecimal volume = hashUnit(asset.getAssetCode() + ":bar-vol:" + i)
                        .multiply(BigDecimal.valueOf(500000))
                        .add(BigDecimal.valueOf(500))
                        .setScale(4, RoundingMode.HALF_UP);
                items.add(new MarketPriceBarDto(
                        meta(asset),
                        barTime,
                        safeTimeframe(timeframe),
                        open.setScale(6, RoundingMode.HALF_UP),
                        high.setScale(6, RoundingMode.HALF_UP),
                        low.setScale(6, RoundingMode.HALF_UP),
                        close.setScale(6, RoundingMode.HALF_UP),
                        volume,
                        providerId()));
            }
        }
        OffsetDateTime responseAt = requestAt.plus(Math.max(0, properties.getMockLatencyMs()), ChronoUnit.MILLIS);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mock", true);
        meta.put("requested_assets", safeAssets(assets).size());
        meta.put("bars_per_asset", safeBarsPerAsset);
        meta.put("timeframe", safeTimeframe(timeframe));
        return MarketProviderFetchResult.success(
                providerId(),
                "bars",
                items,
                200,
                requestAt,
                responseAt,
                "{\"provider\":\"mock\",\"kind\":\"bars\",\"record_count\":" + items.size() + "}",
                meta);
    }

    @Override
    public MarketProviderFetchResult<MarketProviderHealthDto> healthCheck() {
        OffsetDateTime requestAt = OffsetDateTime.now();
        long latency = Math.max(1, properties.getMockLatencyMs());
        OffsetDateTime responseAt = requestAt.plus(latency, ChronoUnit.MILLIS);
        MarketProviderHealthDto health = new MarketProviderHealthDto(
                providerId(),
                MarketProviderHealthStatus.HEALTHY,
                "mock provider is healthy",
                latency,
                responseAt);
        return MarketProviderFetchResult.success(
                providerId(),
                "health-check",
                List.of(health),
                200,
                requestAt,
                responseAt,
                "{\"provider\":\"mock\",\"status\":\"HEALTHY\"}",
                Map.of("mock", true, "status", "HEALTHY"));
    }

    private List<AssetUniverseEntity> safeAssets(List<AssetUniverseEntity> assets) {
        return assets == null ? List.of() : assets;
    }

    private String safeTimeframe(String timeframe) {
        if (timeframe == null || timeframe.isBlank()) {
            return "1m";
        }
        return timeframe.trim().toLowerCase();
    }

    private MarketAssetMetaDto meta(AssetUniverseEntity asset) {
        return new MarketAssetMetaDto(
                safe(asset.getAssetCode()),
                safe(asset.getAssetName()),
                safe(asset.getCountry()),
                safe(asset.getTheme()),
                asset.getAssetType() == null ? "STOCK" : asset.getAssetType().name());
    }

    private BigDecimal basePrice(AssetUniverseEntity asset) {
        return hashUnit(safe(asset.getAssetCode()) + ":base")
                .multiply(BigDecimal.valueOf(490))
                .add(BigDecimal.valueOf(10))
                .setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal hashUnit(String seed) {
        int hash = Math.abs((seed == null ? "" : seed).hashCode());
        return BigDecimal.valueOf(hash % 10000).divide(BigDecimal.valueOf(10000), 6, RoundingMode.HALF_UP);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

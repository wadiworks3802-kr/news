package com.wangbyul.gnd.api.service.signal;

import com.wangbyul.gnd.api.service.signal.model.MarketTrendSignalResult;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
import com.wangbyul.gnd.core.domain.MarketRegimeType;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 시장 동향 엔진.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class MarketTrendSignalService {

    private final MarketPriceBarRepository marketPriceBarRepository;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final AssetUniverseRepository assetUniverseRepository;

    public MarketTrendSignalService(
            MarketPriceBarRepository marketPriceBarRepository,
            MarketQuoteSnapshotRepository marketQuoteSnapshotRepository,
            AssetUniverseRepository assetUniverseRepository) {
        this.marketPriceBarRepository = marketPriceBarRepository;
        this.marketQuoteSnapshotRepository = marketQuoteSnapshotRepository;
        this.assetUniverseRepository = assetUniverseRepository;
    }

    public MarketTrendSignalResult analyze(AssetUniverseEntity asset) {
        List<MarketPriceBarEntity> bars = marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(
                asset.getAssetCode(),
                "D1");

        BigDecimal swingScore = computeSwingScore(bars);
        BigDecimal themeStrength = computeThemeStrength(asset);
        MarketRegimeType regime = resolveRegime(swingScore, themeStrength);

        return new MarketTrendSignalResult(
                regime,
                themeStrength,
                swingScore);
    }

    private BigDecimal computeSwingScore(List<MarketPriceBarEntity> bars) {
        if (bars.size() < 5) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal latest = bars.get(0).getClosePrice();
        BigDecimal oldest = bars.get(Math.min(19, bars.size() - 1)).getClosePrice();
        BigDecimal trend = SignalMath.safeDivide(latest.subtract(oldest), oldest);
        BigDecimal ma5 = averageClose(bars, 5);
        BigDecimal ma20 = averageClose(bars, Math.min(20, bars.size()));
        BigDecimal maDiff = SignalMath.safeDivide(ma5.subtract(ma20), ma20);
        return SignalMath.clampScore(trend.multiply(BigDecimal.valueOf(0.7d)).add(maDiff.multiply(BigDecimal.valueOf(0.3d))));
    }

    private BigDecimal computeThemeStrength(AssetUniverseEntity asset) {
        if (asset.getTheme() == null || asset.getTheme().isBlank()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        List<AssetUniverseEntity> themeAssets = assetUniverseRepository.findTop200ByCountryAndThemeAndActiveTrueOrderByUpdatedAtDesc(
                asset.getCountry(),
                asset.getTheme());

        if (themeAssets.isEmpty()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (AssetUniverseEntity item : themeAssets) {
            MarketQuoteSnapshotEntity quote = marketQuoteSnapshotRepository.findTop1ByAssetCodeOrderBySnapshotUtcDesc(item.getAssetCode())
                    .orElse(null);
            if (quote == null || quote.getChangePct() == null) {
                continue;
            }
            sum = sum.add(quote.getChangePct());
            count++;
        }

        if (count == 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal avgChangePct = sum.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
        return SignalMath.clampScore(avgChangePct.divide(BigDecimal.valueOf(10d), 6, RoundingMode.HALF_UP));
    }

    private MarketRegimeType resolveRegime(BigDecimal swingScore, BigDecimal themeStrength) {
        BigDecimal joint = swingScore.multiply(BigDecimal.valueOf(0.65d)).add(themeStrength.multiply(BigDecimal.valueOf(0.35d)));
        if (joint.compareTo(BigDecimal.valueOf(0.15d)) >= 0) {
            return MarketRegimeType.RISK_ON;
        }
        if (joint.compareTo(BigDecimal.valueOf(-0.15d)) <= 0) {
            return MarketRegimeType.RISK_OFF;
        }
        return MarketRegimeType.MIXED;
    }

    private BigDecimal averageClose(List<MarketPriceBarEntity> bars, int count) {
        if (bars.isEmpty() || count <= 0) {
            return BigDecimal.ZERO;
        }
        int safeCount = Math.min(count, bars.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < safeCount; i++) {
            sum = sum.add(bars.get(i).getClosePrice());
        }
        return sum.divide(BigDecimal.valueOf(safeCount), 6, RoundingMode.HALF_UP);
    }
}


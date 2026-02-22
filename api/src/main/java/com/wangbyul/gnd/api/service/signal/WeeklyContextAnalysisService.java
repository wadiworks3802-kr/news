package com.wangbyul.gnd.api.service.signal;

import com.wangbyul.gnd.api.service.signal.model.WeeklyContextResult;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.NewsRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 최근 1주 뉴스+차트 맥락 점수 계산 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class WeeklyContextAnalysisService {

    private static final List<String> POSITIVE_WORDS = List.of(
            "surge", "rise", "growth", "beat", "expand", "record", "upgrade",
            "상승", "호재", "증가", "개선", "반등");
    private static final List<String> NEGATIVE_WORDS = List.of(
            "drop", "fall", "risk", "slump", "miss", "downgrade", "recession",
            "하락", "악재", "둔화", "위기", "침체");

    private final NewsRepository newsRepository;
    private final MarketPriceBarRepository marketPriceBarRepository;

    public WeeklyContextAnalysisService(
            NewsRepository newsRepository,
            MarketPriceBarRepository marketPriceBarRepository) {
        this.newsRepository = newsRepository;
        this.marketPriceBarRepository = marketPriceBarRepository;
    }

    public WeeklyContextResult analyze(AssetUniverseEntity asset) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(7);
        List<NewsEntity> news = newsRepository.findTop500ByCountryAndPubUtcAfterOrderByPubUtcDesc(asset.getCountry(), since);
        int matchedNews = 0;
        int positiveHits = 0;
        int negativeHits = 0;

        for (NewsEntity item : news) {
            String text = normalize(item.getTitleRaw() + " " + item.getSummaryKo() + " " + item.getBodyRaw());
            if (!matchesAsset(text, asset)) {
                continue;
            }
            matchedNews++;
            positiveHits += countHits(text, POSITIVE_WORDS);
            negativeHits += countHits(text, NEGATIVE_WORDS);
        }

        BigDecimal posRatio;
        BigDecimal negRatio;
        int totalHits = positiveHits + negativeHits;
        if (totalHits == 0) {
            posRatio = BigDecimal.valueOf(0.5d);
            negRatio = BigDecimal.valueOf(0.5d);
        } else {
            posRatio = BigDecimal.valueOf((double) positiveHits / totalHits).setScale(4, RoundingMode.HALF_UP);
            negRatio = BigDecimal.valueOf((double) negativeHits / totalHits).setScale(4, RoundingMode.HALF_UP);
        }

        List<MarketPriceBarEntity> bars = marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(asset.getAssetCode(), "D1");
        BigDecimal priceTrend = computePriceTrendScore(bars);
        BigDecimal volumeTrend = computeVolumeTrendScore(bars);
        BigDecimal volatility = computeVolatilityScore(bars);

        BigDecimal newsBalance = posRatio.subtract(negRatio);
        BigDecimal score = newsBalance.multiply(BigDecimal.valueOf(0.45d))
                .add(priceTrend.multiply(BigDecimal.valueOf(0.35d)))
                .add(volumeTrend.multiply(BigDecimal.valueOf(0.20d)))
                .subtract(volatility.multiply(BigDecimal.valueOf(0.10d)));

        return new WeeklyContextResult(
                matchedNews,
                posRatio,
                negRatio,
                SignalMath.clampScore(priceTrend),
                SignalMath.clampScore(volumeTrend),
                SignalMath.clampScore(volatility),
                SignalMath.clampScore(score));
    }

    private BigDecimal computePriceTrendScore(List<MarketPriceBarEntity> bars) {
        if (bars.size() < 7) {
            return BigDecimal.ZERO;
        }
        BigDecimal latest = bars.get(0).getClosePrice();
        BigDecimal oldest = bars.get(6).getClosePrice();
        return SignalMath.clampScore(SignalMath.safeDivide(latest.subtract(oldest), oldest));
    }

    private BigDecimal computeVolumeTrendScore(List<MarketPriceBarEntity> bars) {
        if (bars.size() < 7) {
            return BigDecimal.ZERO;
        }
        BigDecimal recent = avgVolume(bars, 0, 3);
        BigDecimal previous = avgVolume(bars, 3, 4);
        if (previous.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return SignalMath.clampScore(SignalMath.safeDivide(recent.subtract(previous), previous));
    }

    private BigDecimal computeVolatilityScore(List<MarketPriceBarEntity> bars) {
        if (bars.size() < 7) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int sample = Math.min(7, bars.size());
        for (int i = 0; i < sample; i++) {
            MarketPriceBarEntity bar = bars.get(i);
            if (bar.getOpenPrice() == null || bar.getOpenPrice().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            BigDecimal range = bar.getHighPrice().subtract(bar.getLowPrice());
            sum = sum.add(SignalMath.safeDivide(range, bar.getOpenPrice()));
        }
        return sample == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(sample), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal avgVolume(List<MarketPriceBarEntity> bars, int offset, int count) {
        int end = Math.min(bars.size(), offset + count);
        if (end <= offset) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = offset; i < end; i++) {
            sum = sum.add(bars.get(i).getVolume() == null ? BigDecimal.ZERO : bars.get(i).getVolume());
        }
        return sum.divide(BigDecimal.valueOf(end - offset), 6, RoundingMode.HALF_UP);
    }

    private boolean matchesAsset(String normalizedText, AssetUniverseEntity asset) {
        String code = normalize(asset.getAssetCode());
        String name = normalize(asset.getAssetName());
        String theme = normalize(asset.getTheme());
        return (!code.isBlank() && normalizedText.contains(code))
                || (!name.isBlank() && normalizedText.contains(name))
                || (!theme.isBlank() && normalizedText.contains(theme));
    }

    private int countHits(String normalizedText, List<String> words) {
        int count = 0;
        for (String word : words) {
            if (normalizedText.contains(word.toLowerCase(Locale.ROOT))) {
                count++;
            }
        }
        return count;
    }

    private String normalize(String raw) {
        return String.valueOf(raw)
                .toLowerCase(Locale.ROOT)
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&[a-zA-Z#0-9]+;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}


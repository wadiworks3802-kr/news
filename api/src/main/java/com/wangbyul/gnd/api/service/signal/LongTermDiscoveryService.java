package com.wangbyul.gnd.api.service.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.service.signal.model.DiscoveryResult;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.NewsRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 6개월 발굴 엔진.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class LongTermDiscoveryService {

    private final NewsRepository newsRepository;
    private final MarketPriceBarRepository marketPriceBarRepository;
    private final ObjectMapper objectMapper;

    public LongTermDiscoveryService(
            NewsRepository newsRepository,
            MarketPriceBarRepository marketPriceBarRepository,
            ObjectMapper objectMapper) {
        this.newsRepository = newsRepository;
        this.marketPriceBarRepository = marketPriceBarRepository;
        this.objectMapper = objectMapper;
    }

    public DiscoveryResult analyze(AssetUniverseEntity asset) {
        OffsetDateTime since = OffsetDateTime.now().minusMonths(6);
        List<NewsEntity> recentNews = newsRepository.findTop500ByCountryAndPubUtcAfterOrderByPubUtcDesc(asset.getCountry(), since);
        List<MarketPriceBarEntity> bars = marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(asset.getAssetCode(), "D1");

        int recentMentions = countMentions(recentNews, asset, 30);
        int previousMentions = countMentions(recentNews, asset, 60) - recentMentions;
        if (previousMentions < 0) {
            previousMentions = 0;
        }

        BigDecimal mentionGrowth = previousMentions == 0
                ? (recentMentions > 0 ? BigDecimal.ONE : BigDecimal.ZERO)
                : BigDecimal.valueOf((double) (recentMentions - previousMentions) / previousMentions);

        BigDecimal trendTurnScore = computeTrendTurnScore(bars);
        BigDecimal volumeShiftScore = computeVolumeShiftScore(bars);
        BigDecimal mentionFactor = SignalMath.clamp01(BigDecimal.valueOf(0.5d).add(mentionGrowth.multiply(BigDecimal.valueOf(0.5d))));
        BigDecimal trendFactor = SignalMath.clamp01(BigDecimal.valueOf(0.5d).add(trendTurnScore.multiply(BigDecimal.valueOf(0.5d))));
        BigDecimal volumeFactor = SignalMath.clamp01(BigDecimal.valueOf(0.5d).add(volumeShiftScore.multiply(BigDecimal.valueOf(0.5d))));
        BigDecimal discoveryScore = SignalMath.clamp01(
                mentionFactor.multiply(BigDecimal.valueOf(0.45d))
                        .add(trendFactor.multiply(BigDecimal.valueOf(0.35d)))
                        .add(volumeFactor.multiply(BigDecimal.valueOf(0.20d))));

        String reasonJson = buildReasonJson(mentionGrowth, trendTurnScore, volumeShiftScore, recentMentions, previousMentions);
        return new DiscoveryResult(discoveryScore, 0, reasonJson);
    }

    private int countMentions(List<NewsEntity> news, AssetUniverseEntity asset, int days) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        int count = 0;
        for (NewsEntity item : news) {
            if (item.getPubUtc() == null || item.getPubUtc().isBefore(cutoff)) {
                continue;
            }
            String text = normalize(item.getTitleRaw() + " " + item.getSummaryKo() + " " + item.getBodyRaw());
            if (text.contains(normalize(asset.getAssetCode()))
                    || text.contains(normalize(asset.getAssetName()))
                    || text.contains(normalize(asset.getTheme()))) {
                count++;
            }
        }
        return count;
    }

    private BigDecimal computeTrendTurnScore(List<MarketPriceBarEntity> bars) {
        if (bars.size() < 60) {
            return BigDecimal.ZERO;
        }
        BigDecimal ma20Now = averageClose(bars, 0, 20);
        BigDecimal ma60Now = averageClose(bars, 0, 60);
        BigDecimal ma20Past = averageClose(bars, 40, 20);
        BigDecimal ma60Past = averageClose(bars, 40, Math.min(60, bars.size() - 40));
        boolean nowUp = ma20Now.compareTo(ma60Now) > 0;
        boolean pastDown = ma20Past.compareTo(ma60Past) <= 0;
        if (nowUp && pastDown) {
            return BigDecimal.valueOf(0.8d);
        }
        if (nowUp) {
            return BigDecimal.valueOf(0.4d);
        }
        return BigDecimal.valueOf(-0.2d);
    }

    private BigDecimal computeVolumeShiftScore(List<MarketPriceBarEntity> bars) {
        if (bars.size() < 60) {
            return BigDecimal.ZERO;
        }
        BigDecimal recent = averageVolume(bars, 0, 20);
        BigDecimal past = averageVolume(bars, 40, 20);
        if (past.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return SignalMath.clampScore(SignalMath.safeDivide(recent.subtract(past), past));
    }

    private BigDecimal averageClose(List<MarketPriceBarEntity> bars, int offset, int count) {
        int end = Math.min(bars.size(), offset + count);
        if (end <= offset) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = offset; i < end; i++) {
            sum = sum.add(bars.get(i).getClosePrice());
        }
        return sum.divide(BigDecimal.valueOf(end - offset), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal averageVolume(List<MarketPriceBarEntity> bars, int offset, int count) {
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

    private String buildReasonJson(
            BigDecimal mentionGrowth,
            BigDecimal trendTurnScore,
            BigDecimal volumeShiftScore,
            int recentMentions,
            int previousMentions) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("mention_growth", mentionGrowth.setScale(4, RoundingMode.HALF_UP));
            payload.put("trend_turn_score", trendTurnScore.setScale(4, RoundingMode.HALF_UP));
            payload.put("volume_shift_score", volumeShiftScore.setScale(4, RoundingMode.HALF_UP));
            payload.put("recent_mentions_30d", recentMentions);
            payload.put("previous_mentions_30d", previousMentions);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"reason-build-failed\"}";
        }
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

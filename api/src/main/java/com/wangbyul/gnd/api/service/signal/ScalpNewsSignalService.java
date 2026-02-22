package com.wangbyul.gnd.api.service.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.service.signal.model.ScalpSignalResult;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.repository.NewsRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 단타 뉴스 엔진.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class ScalpNewsSignalService {

    private static final List<String> POSITIVE_WORDS = List.of(
            "surge", "rise", "beat", "growth", "upgrade", "expand", "record",
            "상승", "급등", "호재", "개선", "증가", "반등");

    private static final List<String> NEGATIVE_WORDS = List.of(
            "drop", "fall", "slump", "miss", "downgrade", "recession", "risk",
            "하락", "급락", "악재", "둔화", "침체", "위기");

    private final NewsRepository newsRepository;
    private final SignalPolicyProperties signalPolicyProperties;
    private final ObjectMapper objectMapper;

    public ScalpNewsSignalService(
            NewsRepository newsRepository,
            SignalPolicyProperties signalPolicyProperties,
            ObjectMapper objectMapper) {
        this.newsRepository = newsRepository;
        this.signalPolicyProperties = signalPolicyProperties;
        this.objectMapper = objectMapper;
    }

    public ScalpSignalResult analyze(AssetUniverseEntity asset) {
        return analyze(asset, OffsetDateTime.now(), signalPolicyProperties.getNewsPriceAlignmentWindowMinutes());
    }

    /**
     * RULE_V1 확률 계산.
     *
     * 1) 뉴스 신뢰도 하한(trust_score_min) 이상만 사용
     * 2) 시그널 시점 이후 뉴스(미래 데이터)는 제외
     * 3) 반감기 기반 시간감쇠(weight = 0.5^(age/half_life))
     * 4) 긍/부정 임계치 이상 감성만 확률 분자에 반영
     */
    public ScalpSignalResult analyze(AssetUniverseEntity asset, OffsetDateTime signalGeneratedAt, Integer alignmentWindowMinutes) {
        OffsetDateTime signalTime = signalGeneratedAt == null ? OffsetDateTime.now() : signalGeneratedAt;
        int configuredWindowMinutes = resolveScalpWindowMinutes();
        int alignmentMinutes = alignmentWindowMinutes == null || alignmentWindowMinutes <= 0
                ? Math.max(1, signalPolicyProperties.getNewsPriceAlignmentWindowMinutes())
                : alignmentWindowMinutes;
        int effectiveWindowMinutes = Math.min(configuredWindowMinutes, alignmentMinutes);
        OffsetDateTime since = signalTime.minusMinutes(effectiveWindowMinutes);

        List<NewsEntity> recent = newsRepository.findTop500ByCountryAndPublishedAtUtcAfterOrderByPublishedAtUtcDesc(
                asset.getCountry(),
                since);
        if (recent.isEmpty()) {
            recent = newsRepository.findTop500ByCountryAndPubUtcAfterOrderByPubUtcDesc(asset.getCountry(), since);
        }

        double positiveMass = 0d;
        double negativeMass = 0d;
        int eligibleCount = 0;
        int filteredTrustCount = 0;
        int filteredFutureCount = 0;
        int neutralSentimentCount = 0;
        double trustSum = 0d;

        BigDecimal trustFloor = normalizeRate(signalPolicyProperties.getNewsTrustScoreMin(), BigDecimal.valueOf(0.4d));
        BigDecimal positiveThreshold = normalizeRate(signalPolicyProperties.getPositiveSentimentThreshold(), BigDecimal.valueOf(0.55d));
        BigDecimal negativeThreshold = normalizeRate(signalPolicyProperties.getNegativeSentimentThreshold(), BigDecimal.valueOf(0.55d));
        int minNewsCount = Math.max(1, signalPolicyProperties.getMinNewsCountForProbability());
        int halfLifeMinutes = Math.max(1, signalPolicyProperties.getNewsDecayHalfLifeMinutes());
        String mode = String.valueOf(signalPolicyProperties.getProbabilityCalculationMode()).toUpperCase(Locale.ROOT);
        boolean ruleV1 = "RULE_V1".equals(mode);

        for (NewsEntity news : recent) {
            String text = normalize(news.getTitleRaw() + " " + news.getSummaryKo() + " " + news.getBodyRaw());
            if (!matchesAsset(text, asset)) {
                continue;
            }

            OffsetDateTime eventTime = firstNonNull(
                    news.getPublishedAtUtc(),
                    news.getPubUtc(),
                    news.getFetchedAtUtc(),
                    news.getFetchUtc(),
                    news.getCreatedAt());
            if (eventTime != null && eventTime.isAfter(signalTime)) {
                filteredFutureCount++;
                continue;
            }

            double trust = news.getTrustScore() == null ? 0.35d : news.getTrustScore().doubleValue();
            if (BigDecimal.valueOf(trust).compareTo(trustFloor) < 0) {
                filteredTrustCount++;
                continue;
            }

            eligibleCount++;
            trustSum += trust;

            int posHits = keywordHits(text, POSITIVE_WORDS);
            int negHits = keywordHits(text, NEGATIVE_WORDS);
            int totalHits = posHits + negHits;
            if (!ruleV1) {
                neutralSentimentCount++;
                continue;
            }
            if (totalHits <= 0) {
                neutralSentimentCount++;
                continue;
            }

            double positiveRatio = (double) posHits / totalHits;
            double negativeRatio = (double) negHits / totalHits;
            double decayWeight = computeDecayWeight(signalTime, eventTime, halfLifeMinutes);
            double weightedTrust = trust * decayWeight;

            if (BigDecimal.valueOf(positiveRatio).compareTo(positiveThreshold) >= 0) {
                positiveMass += weightedTrust * positiveRatio;
            }
            if (BigDecimal.valueOf(negativeRatio).compareTo(negativeThreshold) >= 0) {
                negativeMass += weightedTrust * negativeRatio;
            }
            if (BigDecimal.valueOf(positiveRatio).compareTo(positiveThreshold) < 0
                    && BigDecimal.valueOf(negativeRatio).compareTo(negativeThreshold) < 0) {
                neutralSentimentCount++;
            }
        }

        boolean insufficientSample = eligibleCount < minNewsCount;
        double totalMass = positiveMass + negativeMass;
        BigDecimal goodProb;
        BigDecimal badProb;
        if (insufficientSample || totalMass <= 0d) {
            goodProb = BigDecimal.valueOf(0.5d);
            badProb = BigDecimal.valueOf(0.5d);
        } else {
            goodProb = SignalMath.clamp01(BigDecimal.valueOf(positiveMass / totalMass));
            badProb = SignalMath.clamp01(BigDecimal.valueOf(negativeMass / totalMass));
        }

        double avgTrust = eligibleCount == 0 ? 0d : trustSum / eligibleCount;
        BigDecimal confidenceFloor = normalizeRate(signalPolicyProperties.getInsufficientSampleConfidenceFloor(), BigDecimal.valueOf(0.2d));
        BigDecimal confidence;
        if (insufficientSample) {
            BigDecimal sampleRatio = BigDecimal.valueOf(eligibleCount)
                    .divide(BigDecimal.valueOf(minNewsCount), 6, RoundingMode.HALF_UP);
            BigDecimal raw = sampleRatio.multiply(BigDecimal.valueOf(avgTrust));
            confidence = SignalMath.clamp01(raw.max(confidenceFloor));
        } else {
            confidence = SignalMath.clamp01(BigDecimal.valueOf(avgTrust));
        }
        BigDecimal scalpScore = SignalMath.clampScore(goodProb.subtract(badProb));

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("mode", ruleV1 ? "RULE_V1" : mode);
        breakdown.put("signal_time_utc", signalTime);
        breakdown.put("window_minutes", effectiveWindowMinutes);
        breakdown.put("min_news_count_for_probability", minNewsCount);
        breakdown.put("news_trust_score_min", trustFloor);
        breakdown.put("positive_sentiment_threshold", positiveThreshold);
        breakdown.put("negative_sentiment_threshold", negativeThreshold);
        breakdown.put("news_decay_half_life_minutes", halfLifeMinutes);
        breakdown.put("eligible_news_count", eligibleCount);
        breakdown.put("filtered_trust_count", filteredTrustCount);
        breakdown.put("filtered_future_count", filteredFutureCount);
        breakdown.put("neutral_sentiment_count", neutralSentimentCount);
        breakdown.put("insufficient_sample", insufficientSample);
        breakdown.put("positive_mass", round6(positiveMass));
        breakdown.put("negative_mass", round6(negativeMass));
        breakdown.put("good_news_probability", goodProb);
        breakdown.put("bad_news_probability", badProb);
        breakdown.put("news_confidence", confidence);

        return new ScalpSignalResult(
                scalpScore,
                goodProb,
                badProb,
                confidence,
                eligibleCount,
                toJson(breakdown));
    }

    private int resolveScalpWindowMinutes() {
        return signalPolicyProperties.getNewsWindowsMinutes().stream()
                .filter(v -> v != null && v > 0 && v <= 60)
                .max(Integer::compareTo)
                .orElse(60);
    }

    private boolean matchesAsset(String normalizedText, AssetUniverseEntity asset) {
        String code = normalize(asset.getAssetCode());
        String name = normalize(asset.getAssetName());
        String theme = normalize(asset.getTheme());
        return (!code.isBlank() && normalizedText.contains(code))
                || (!name.isBlank() && normalizedText.contains(name))
                || (!theme.isBlank() && normalizedText.contains(theme));
    }

    private int keywordHits(String normalizedText, List<String> words) {
        int hits = 0;
        for (String word : words) {
            if (normalizedText.contains(word.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return hits;
    }

    private String normalize(String raw) {
        return String.valueOf(raw)
                .toLowerCase(Locale.ROOT)
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&[a-zA-Z#0-9]+;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private OffsetDateTime firstNonNull(OffsetDateTime... values) {
        for (OffsetDateTime value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private double computeDecayWeight(OffsetDateTime signalTime, OffsetDateTime eventTime, int halfLifeMinutes) {
        if (signalTime == null || eventTime == null) {
            return 1d;
        }
        long ageMinutes = Math.max(0L, ChronoUnit.MINUTES.between(eventTime, signalTime));
        return Math.pow(0.5d, (double) ageMinutes / Math.max(1, halfLifeMinutes));
    }

    private BigDecimal normalizeRate(BigDecimal value, BigDecimal fallback) {
        if (value == null) {
            return fallback;
        }
        return SignalMath.clamp01(value);
    }

    private BigDecimal round6(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}

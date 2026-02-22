package com.wangbyul.gnd.api.service.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.SignalPolicyProperties;
import com.wangbyul.gnd.api.service.signal.model.ScalpSignalResult;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
import com.wangbyul.gnd.core.domain.NewsAssetLinkEntity;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.domain.NewsLinkType;
import com.wangbyul.gnd.core.domain.TickerAliasDictionaryEntity;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.NewsAssetLinkRepository;
import com.wangbyul.gnd.core.repository.NewsRepository;
import com.wangbyul.gnd.core.repository.TickerAliasDictionaryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
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

    private static final Map<String, List<String>> EVENT_KEYWORDS = Map.ofEntries(
            Map.entry("ORDER", List.of("수주", "계약", "contract", "order", "deal", "협약")),
            Map.entry("REGULATION", List.of("규제", "승인", "허가", "regulation", "approval", "sanction")),
            Map.entry("EARNINGS", List.of("실적", "가이던스", "earnings", "guidance", "revenue", "profit", "beat", "miss")),
            Map.entry("INCIDENT", List.of("사고", "화재", "리콜", "소송", "fraud", "accident", "explosion", "outage")),
            Map.entry("SUPPLY_CHAIN", List.of("공급망", "공장", "생산", "shutdown", "factory", "plant")),
            Map.entry("MACRO", List.of("금리", "인플레이션", "환율", "gdp", "cpi", "fomc", "fed", "macro")));

    private static final Map<String, List<String>> THEME_KEYWORDS = Map.ofEntries(
            Map.entry("AI", List.of("ai", "인공지능", "llm", "gpu", "모델")),
            Map.entry("SEMICONDUCTOR", List.of("반도체", "semicon", "chip", "wafer", "fab")),
            Map.entry("DEFENSE", List.of("방산", "defense", "군수", "missile", "munition")),
            Map.entry("SPACE", List.of("우주", "space", "satellite", "launch", "aerospace")),
            Map.entry("ROBOTICS", List.of("로봇", "robot", "automation")),
            Map.entry("ENERGY", List.of("에너지", "전력", "oil", "gas", "battery", "renewable")),
            Map.entry("RESOURCE", List.of("자원", "광물", "metal", "gold", "silver", "copper", "lithium")));

    private final NewsRepository newsRepository;
    private final NewsAssetLinkRepository newsAssetLinkRepository;
    private final TickerAliasDictionaryRepository tickerAliasDictionaryRepository;
    private final MarketPriceBarRepository marketPriceBarRepository;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final SignalPolicyProperties signalPolicyProperties;
    private final ObjectMapper objectMapper;

    public ScalpNewsSignalService(
            NewsRepository newsRepository,
            NewsAssetLinkRepository newsAssetLinkRepository,
            TickerAliasDictionaryRepository tickerAliasDictionaryRepository,
            MarketPriceBarRepository marketPriceBarRepository,
            MarketQuoteSnapshotRepository marketQuoteSnapshotRepository,
            SignalPolicyProperties signalPolicyProperties,
            ObjectMapper objectMapper) {
        this.newsRepository = newsRepository;
        this.newsAssetLinkRepository = newsAssetLinkRepository;
        this.tickerAliasDictionaryRepository = tickerAliasDictionaryRepository;
        this.marketPriceBarRepository = marketPriceBarRepository;
        this.marketQuoteSnapshotRepository = marketQuoteSnapshotRepository;
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
        OffsetDateTime extendedSince = signalTime.minusMinutes(Math.max(2, effectiveWindowMinutes * 2L));

        List<NewsEntity> recent = newsRepository.findTop500ByCountryAndPublishedAtUtcAfterOrderByPublishedAtUtcDesc(
                asset.getCountry(),
                extendedSince);
        if (recent.isEmpty()) {
            recent = newsRepository.findTop500ByCountryAndPubUtcAfterOrderByPubUtcDesc(asset.getCountry(), extendedSince);
        }

        BigDecimal trustFloor = normalizeRate(signalPolicyProperties.getNewsTrustScoreMin(), BigDecimal.valueOf(0.4d));
        BigDecimal positiveThreshold = normalizeRate(signalPolicyProperties.getPositiveSentimentThreshold(), BigDecimal.valueOf(0.55d));
        BigDecimal negativeThreshold = normalizeRate(signalPolicyProperties.getNegativeSentimentThreshold(), BigDecimal.valueOf(0.55d));
        int minNewsCount = Math.max(1, signalPolicyProperties.getMinNewsCountForProbability());
        int halfLifeMinutes = Math.max(1, signalPolicyProperties.getNewsDecayHalfLifeMinutes());
        int translationDelayPenaltyThresholdSeconds = Math.max(
                60,
                signalPolicyProperties.getTranslationDelayPenaltyThresholdSeconds() == null
                        ? 900
                        : signalPolicyProperties.getTranslationDelayPenaltyThresholdSeconds());
        String mode = String.valueOf(signalPolicyProperties.getProbabilityCalculationMode()).toUpperCase(Locale.ROOT);
        boolean ruleV1 = "RULE_V1".equals(mode);

        List<String> aliases = resolveAliases(asset);
        List<MarketPriceBarEntity> bars1m = sortBarsAsc(
                marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(asset.getAssetCode(), "1m"));
        List<MarketQuoteSnapshotEntity> quotes = sortQuotesAsc(
                marketQuoteSnapshotRepository.findTop120ByAssetCodeOrderBySnapshotUtcDesc(asset.getAssetCode()));
        Map<String, Integer> dedupCounts = buildDedupGroupCounts(recent);

        List<ScalpEvidence> currentMapped = new ArrayList<>();
        int previousMappedCount = 0;
        int previousEligibleCount = 0;
        int filteredTrustCount = 0;
        int filteredFutureCount = 0;
        int neutralSentimentCount = 0;

        for (NewsEntity news : recent) {
            String text = normalize((news.getTitleRaw() == null ? "" : news.getTitleRaw())
                    + " " + (news.getSummaryKo() == null ? "" : news.getSummaryKo())
                    + " " + (news.getBodyRaw() == null ? "" : news.getBodyRaw()));
            LinkEvaluation link = evaluateLink(asset, news, text, aliases);
            if (link == null) {
                continue;
            }

            OffsetDateTime eventTime = firstNonNull(
                    news.getPublishedAtUtc(),
                    news.getPubUtc(),
                    news.getFetchedAtUtc(),
                    news.getFetchUtc(),
                    news.getCreatedAt());
            int posHits = keywordHits(text, POSITIVE_WORDS);
            int negHits = keywordHits(text, NEGATIVE_WORDS);
            String eventType = classifyEventType(text);
            String impactDirection = classifyImpactDirection(posHits, negHits, eventType);
            String impactHorizon = classifyImpactHorizon(text, eventType);
            int duplicateCount = dedupCounts.getOrDefault(dedupKey(news), 1);
            long translationDelaySeconds = translationDelaySeconds(news);

            Map<String, Object> keywordHits = new LinkedHashMap<>(link.keywordHits());
            keywordHits.put("positive_word_hits", posHits);
            keywordHits.put("negative_word_hits", negHits);
            keywordHits.put("event_type", eventType);
            keywordHits.put("impact_direction", impactDirection);
            keywordHits.put("impact_horizon", impactHorizon);
            keywordHits.put("duplicate_count", duplicateCount);
            keywordHits.put("translation_delay_seconds", translationDelaySeconds);
            upsertNewsAssetLink(news, asset, link, keywordHits, eventType, impactDirection, impactHorizon);

            boolean inCurrentWindow = eventTime == null || (!eventTime.isBefore(since) && !eventTime.isAfter(signalTime));
            boolean inPreviousWindow = eventTime != null && eventTime.isBefore(since) && !eventTime.isBefore(extendedSince);
            if (!inCurrentWindow && !inPreviousWindow) {
                continue;
            }

            ScalpEvidence evidence = buildEvidence(
                    news,
                    eventTime,
                    signalTime,
                    link,
                    text,
                    posHits,
                    negHits,
                    duplicateCount,
                    translationDelaySeconds,
                    translationDelayPenaltyThresholdSeconds,
                    halfLifeMinutes,
                    trustFloor,
                    bars1m,
                    quotes);

            if (evidence.futureFiltered()) {
                filteredFutureCount++;
            }
            if (evidence.trustFiltered()) {
                filteredTrustCount++;
            }
            if (!evidence.hasStrongSentiment(positiveThreshold, negativeThreshold) || !ruleV1) {
                neutralSentimentCount++;
            }

            if (inPreviousWindow) {
                previousMappedCount++;
                if (evidence.eligible()) {
                    previousEligibleCount++;
                }
            }
            if (inCurrentWindow) {
                currentMapped.add(evidence);
            }
        }

        ScalpAggregation agg = aggregateEvidence(
                asset,
                signalTime,
                currentMapped,
                bars1m,
                quotes,
                previousMappedCount,
                previousEligibleCount,
                minNewsCount,
                positiveThreshold,
                negativeThreshold);
        BigDecimal goodProb = agg.goodProb();
        BigDecimal badProb = agg.badProb();
        BigDecimal confidence = agg.confidence();
        BigDecimal scalpScore = agg.scalpScore();

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("mode", ruleV1 ? "RULE_V1" : mode);
        breakdown.put("data_state", agg.dataState());
        breakdown.put("signal_time_utc", signalTime);
        breakdown.put("window_minutes", effectiveWindowMinutes);
        breakdown.put("extended_window_minutes", Math.max(2, effectiveWindowMinutes * 2L));
        breakdown.put("min_news_count_for_probability", minNewsCount);
        breakdown.put("news_trust_score_min", trustFloor);
        breakdown.put("positive_sentiment_threshold", positiveThreshold);
        breakdown.put("negative_sentiment_threshold", negativeThreshold);
        breakdown.put("news_decay_half_life_minutes", halfLifeMinutes);
        breakdown.put("mapped_news_count", currentMapped.size());
        breakdown.put("eligible_news_count", agg.eligibleCount());
        breakdown.put("previous_window_mapped_news_count", previousMappedCount);
        breakdown.put("previous_window_eligible_news_count", previousEligibleCount);
        breakdown.put("news_volume_change_rate", round6(agg.newsVolumeChangeRate()));
        breakdown.put("eligible_news_change_rate", round6(agg.eligibleNewsChangeRate()));
        breakdown.put("filtered_trust_count", filteredTrustCount);
        breakdown.put("filtered_future_count", filteredFutureCount);
        breakdown.put("neutral_sentiment_count", neutralSentimentCount);
        breakdown.put("insufficient_sample", agg.insufficientSample());
        breakdown.put("positive_mass", round6(agg.positiveMass()));
        breakdown.put("negative_mass", round6(agg.negativeMass()));
        breakdown.put("positive_mass_normalized", round6(agg.positiveMassNormalized()));
        breakdown.put("negative_mass_normalized", round6(agg.negativeMassNormalized()));
        breakdown.put("good_news_probability", goodProb);
        breakdown.put("bad_news_probability", badProb);
        breakdown.put("news_confidence", confidence);
        breakdown.put("uncertainty_score", SignalMath.clamp01(BigDecimal.ONE.subtract(confidence)));
        breakdown.put("top_positive_factors", agg.topPositiveFactors());
        breakdown.put("top_negative_factors", agg.topNegativeFactors());
        breakdown.put("news_alignment_result", agg.newsAlignmentResult());
        breakdown.put("data_freshness", agg.dataFreshness());
        breakdown.put("dedup_result", agg.dedupResult());
        breakdown.put("mapped_news_preview", agg.mappedNewsPreview());
        breakdown.put("missing_requirements", agg.missingRequirements());
        breakdown.put("change_conditions", agg.changeConditions());
        breakdown.put("explain_text", agg.explainText());

        return new ScalpSignalResult(
                scalpScore,
                goodProb,
                badProb,
                confidence,
                agg.eligibleCount(),
                toJson(breakdown));
    }

    private int resolveScalpWindowMinutes() {
        return signalPolicyProperties.getNewsWindowsMinutes().stream()
                .filter(v -> v != null && v > 0 && v <= 60)
                .max(Integer::compareTo)
                .orElse(60);
    }

    private List<String> resolveAliases(AssetUniverseEntity asset) {
        List<String> aliases = new ArrayList<>();
        if (asset == null) {
            return aliases;
        }
        addAlias(aliases, asset.getAssetCode());
        addAlias(aliases, asset.getAssetName());
        tickerAliasDictionaryRepository.findByAssetCodeAndActiveTrueOrderByAliasTypeAsc(asset.getAssetCode()).stream()
                .map(TickerAliasDictionaryEntity::getAliasValue)
                .forEach(v -> addAlias(aliases, v));
        return aliases.stream().distinct().toList();
    }

    private void addAlias(List<String> aliases, String raw) {
        String v = normalize(raw);
        if (!v.isBlank() && v.length() >= 2) {
            aliases.add(v);
        }
    }

    private LinkEvaluation evaluateLink(AssetUniverseEntity asset, NewsEntity news, String normalizedText, List<String> aliases) {
        String code = normalize(asset.getAssetCode());
        String name = normalize(asset.getAssetName());
        String theme = normalize(asset.getTheme());
        String themeCode = normalizeThemeCode(asset);
        boolean codeHit = !code.isBlank() && containsToken(normalizedText, code);
        boolean nameHit = !name.isBlank() && normalizedText.contains(name);
        List<String> aliasHits = aliases.stream()
                .filter(alias -> !alias.equals(code) && !alias.equals(name))
                .filter(alias -> normalizedText.contains(alias))
                .limit(5)
                .toList();
        boolean aliasHit = !aliasHits.isEmpty();
        double themeMatchScore = computeThemeMatchScore(normalizedText, theme, themeCode);
        boolean themeHit = themeMatchScore >= 0.35d;
        if (!codeHit && !nameHit && !aliasHit && !themeHit) {
            return null;
        }
        NewsLinkType linkType = (codeHit || nameHit || aliasHit) ? NewsLinkType.DIRECT : NewsLinkType.THEME;
        String linkMethod = aliasHit && (codeHit || nameHit || themeHit) ? "HYBRID"
                : (aliasHit ? "DICT" : "RULE");
        double confidence = 0d;
        if (codeHit) confidence += 0.45d;
        if (nameHit) confidence += 0.40d;
        if (aliasHit) confidence += 0.50d;
        confidence += Math.min(0.25d, themeMatchScore * 0.25d);
        if (linkType == NewsLinkType.THEME && !(codeHit || nameHit || aliasHit)) {
            confidence = Math.min(confidence, 0.60d);
        }
        confidence = Math.max(0.10d, Math.min(1d, confidence));

        Map<String, Object> keywordHits = new LinkedHashMap<>();
        keywordHits.put("code_hit", codeHit);
        keywordHits.put("name_hit", nameHit);
        keywordHits.put("alias_hits", aliasHits);
        keywordHits.put("theme_hit", themeHit);
        keywordHits.put("theme_match_score", round6(themeMatchScore));
        keywordHits.put("asset_theme_code", themeCode);
        keywordHits.put("asset_theme", asset.getTheme());
        keywordHits.put("news_category", news.getCategory() == null ? null : news.getCategory().name());

        return new LinkEvaluation(
                linkType,
                BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP),
                linkMethod,
                keywordHits,
                aliasHits.isEmpty() ? null : aliasHits.get(0),
                BigDecimal.valueOf(themeMatchScore).setScale(4, RoundingMode.HALF_UP));
    }

    private void upsertNewsAssetLink(
            NewsEntity news,
            AssetUniverseEntity asset,
            LinkEvaluation link,
            Map<String, Object> keywordHits,
            String eventType,
            String impactDirection,
            String impactHorizon) {
        NewsAssetLinkEntity entity = newsAssetLinkRepository
                .findByNewsIdAndAssetCodeAndLinkType(news.getId(), asset.getAssetCode(), link.linkType())
                .orElseGet(NewsAssetLinkEntity::new);
        entity.setNewsId(news.getId());
        entity.setAssetCode(asset.getAssetCode());
        entity.setLinkType(link.linkType());
        entity.setConfidence(link.linkConfidence());
        entity.setLinkConfidence(link.linkConfidence());
        entity.setLinkMethod(link.linkMethod());
        entity.setKeywordHitsJson(toJson(keywordHits));
        entity.setTickerAliasHit(link.tickerAliasHit());
        entity.setThemeMatchScore(link.themeMatchScore());
        entity.setEventType(eventType);
        entity.setImpactDirection(impactDirection);
        entity.setImpactHorizon(impactHorizon);
        entity.setTraceId(traceId());
        newsAssetLinkRepository.save(entity);
    }

    private String classifyEventType(String normalizedText) {
        for (Map.Entry<String, List<String>> entry : EVENT_KEYWORDS.entrySet()) {
            if (keywordHits(normalizedText, entry.getValue()) > 0) {
                return entry.getKey();
            }
        }
        return "GENERAL";
    }

    private String classifyImpactDirection(int posHits, int negHits, String eventType) {
        if (posHits > 0 && negHits > 0) {
            return "MIXED";
        }
        if (negHits > 0) {
            return "NEGATIVE";
        }
        if (posHits > 0) {
            return "POSITIVE";
        }
        if ("INCIDENT".equals(eventType)) {
            return "NEGATIVE";
        }
        return "NEUTRAL";
    }

    private String classifyImpactHorizon(String normalizedText, String eventType) {
        if (normalizedText.contains("today") || normalizedText.contains("오늘") || normalizedText.contains("장중")
                || normalizedText.contains("breaking")) {
            return "INTRADAY";
        }
        return switch (eventType) {
            case "SUPPLY_CHAIN", "MACRO" -> "MID";
            default -> "SHORT";
        };
    }

    private List<MarketPriceBarEntity> sortBarsAsc(List<MarketPriceBarEntity> rows) {
        List<MarketPriceBarEntity> copy = new ArrayList<>(rows == null ? List.of() : rows);
        copy.sort(Comparator.comparing(this::barTime, Comparator.nullsLast(Comparator.naturalOrder())));
        return copy;
    }

    private List<MarketQuoteSnapshotEntity> sortQuotesAsc(List<MarketQuoteSnapshotEntity> rows) {
        List<MarketQuoteSnapshotEntity> copy = new ArrayList<>(rows == null ? List.of() : rows);
        copy.sort(Comparator.comparing(this::quoteTime, Comparator.nullsLast(Comparator.naturalOrder())));
        return copy;
    }

    private Map<String, Integer> buildDedupGroupCounts(List<NewsEntity> rows) {
        Map<String, Integer> result = new HashMap<>();
        for (NewsEntity news : rows) {
            result.merge(dedupKey(news), 1, Integer::sum);
        }
        return result;
    }

    private String dedupKey(NewsEntity news) {
        if (news.getDedupGroupId() != null && !news.getDedupGroupId().isBlank()) {
            return "dg:" + news.getDedupGroupId();
        }
        if (news.getContentHash() != null && !news.getContentHash().isBlank()) {
            return "ch:" + news.getContentHash();
        }
        return "id:" + String.valueOf(news.getId());
    }

    private ScalpEvidence buildEvidence(
            NewsEntity news,
            OffsetDateTime eventTime,
            OffsetDateTime signalTime,
            LinkEvaluation link,
            String normalizedText,
            int posHits,
            int negHits,
            int duplicateCount,
            long translationDelaySeconds,
            int translationDelayPenaltyThresholdSeconds,
            int halfLifeMinutes,
            BigDecimal trustFloor,
            List<MarketPriceBarEntity> bars1m,
            List<MarketQuoteSnapshotEntity> quotesAsc) {
        double trust = news.getTrustScore() == null ? 0.35d : news.getTrustScore().doubleValue();
        boolean futureFiltered = eventTime != null && eventTime.isAfter(signalTime);
        boolean trustFiltered = BigDecimal.valueOf(trust).compareTo(trustFloor) < 0;
        boolean invalidPublishFetchOrder = news.getPublishedAtUtc() != null
                && news.getFetchedAtUtc() != null
                && news.getPublishedAtUtc().isAfter(news.getFetchedAtUtc().plusSeconds(5));

        int totalHits = posHits + negHits;
        double positiveRatio = totalHits <= 0 ? 0d : (double) posHits / totalHits;
        double negativeRatio = totalHits <= 0 ? 0d : (double) negHits / totalHits;
        double dedupPenaltyRate = duplicateCount <= 1 ? 0d : Math.min(0.65d, (duplicateCount - 1) * 0.18d);
        double dedupWeight = 1d - dedupPenaltyRate;
        double translationPenaltyRate = translationDelaySeconds <= 0
                ? 0d
                : Math.min(0.40d, ((double) translationDelaySeconds / Math.max(1, translationDelayPenaltyThresholdSeconds)) * 0.15d);
        double alignmentPenaltyRate = (futureFiltered ? 0.30d : 0d) + (invalidPublishFetchOrder ? 0.12d : 0d);
        alignmentPenaltyRate = Math.min(0.60d, alignmentPenaltyRate);
        double decayWeight = computeDecayWeight(signalTime, eventTime, halfLifeMinutes);
        double priceReaction5m = computeReactionFromBars(bars1m, eventTime, 5);
        double priceReaction15m = computeReactionFromBars(bars1m, eventTime, 15);
        double priceReaction1h = computeReactionFromBars(bars1m, eventTime, 60);
        double volumeChangeRatio = computeVolumeChangeRatio(bars1m, eventTime);
        double priceCoverage = 0d;
        if (priceReaction5m != 0d || priceReaction15m != 0d || priceReaction1h != 0d || volumeChangeRatio != 0d) {
            priceCoverage = 1d;
        } else if (!quotesAsc.isEmpty()) {
            priceCoverage = 0.5d;
        }

        return new ScalpEvidence(
                news,
                eventTime,
                link.linkConfidence().doubleValue(),
                link.linkMethod(),
                link.tickerAliasHit(),
                link.themeMatchScore() == null ? 0d : link.themeMatchScore().doubleValue(),
                posHits,
                negHits,
                positiveRatio,
                negativeRatio,
                trust,
                duplicateCount,
                dedupWeight,
                translationDelaySeconds,
                translationPenaltyRate,
                futureFiltered,
                trustFiltered,
                invalidPublishFetchOrder,
                decayWeight,
                classifyEventType(normalizedText),
                classifyImpactDirection(posHits, negHits, classifyEventType(normalizedText)),
                classifyImpactHorizon(normalizedText, classifyEventType(normalizedText)),
                priceReaction5m,
                priceReaction15m,
                priceReaction1h,
                volumeChangeRatio,
                priceCoverage);
    }

    private double computeReactionFromBars(List<MarketPriceBarEntity> barsAsc, OffsetDateTime eventTime, int minutesAfter) {
        if (barsAsc == null || barsAsc.isEmpty() || eventTime == null) {
            return 0d;
        }
        OffsetDateTime targetTime = eventTime.plusMinutes(minutesAfter);
        MarketPriceBarEntity base = null;
        MarketPriceBarEntity target = null;
        for (MarketPriceBarEntity bar : barsAsc) {
            OffsetDateTime bt = barTime(bar);
            if (bt == null) {
                continue;
            }
            if (!bt.isAfter(eventTime)) {
                base = bar;
            }
            if (target == null && !bt.isBefore(targetTime)) {
                target = bar;
            }
        }
        if (base == null || target == null || base.getClosePrice() == null || target.getClosePrice() == null
                || base.getClosePrice().compareTo(BigDecimal.ZERO) == 0) {
            return 0d;
        }
        return target.getClosePrice()
                .subtract(base.getClosePrice())
                .divide(base.getClosePrice(), 6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private double computeVolumeChangeRatio(List<MarketPriceBarEntity> barsAsc, OffsetDateTime eventTime) {
        if (barsAsc == null || barsAsc.isEmpty() || eventTime == null) {
            return 0d;
        }
        List<BigDecimal> before = new ArrayList<>();
        MarketPriceBarEntity after = null;
        for (MarketPriceBarEntity bar : barsAsc) {
            OffsetDateTime bt = barTime(bar);
            if (bt == null || bar.getVolume() == null) {
                continue;
            }
            if (!bt.isAfter(eventTime)) {
                before.add(bar.getVolume());
                if (before.size() > 20) {
                    before.remove(0);
                }
            } else if (after == null) {
                after = bar;
            }
        }
        if (before.isEmpty() || after == null) {
            return 0d;
        }
        BigDecimal avg = before.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(before.size()), 6, RoundingMode.HALF_UP);
        if (avg.compareTo(BigDecimal.ZERO) == 0) {
            return 0d;
        }
        return after.getVolume().subtract(avg).divide(avg, 6, RoundingMode.HALF_UP).doubleValue();
    }

    private OffsetDateTime barTime(MarketPriceBarEntity row) {
        return row == null ? null : (row.getBarTimeUtc() != null ? row.getBarTimeUtc() : row.getBarTime());
    }

    private OffsetDateTime quoteTime(MarketQuoteSnapshotEntity row) {
        return row == null ? null : (row.getQuoteTimeUtc() != null ? row.getQuoteTimeUtc() : row.getSnapshotUtc());
    }

    private double computeThemeMatchScore(String normalizedText, String theme, String themeCode) {
        double score = 0d;
        String normalizedTheme = normalize(theme);
        if (!normalizedTheme.isBlank() && normalizedText.contains(normalizedTheme)) {
            score += 0.45d;
        }
        List<String> themeWords = themeCode == null ? List.of() : THEME_KEYWORDS.getOrDefault(themeCode, List.of());
        int hits = keywordHits(normalizedText, themeWords);
        if (hits > 0) {
            score += Math.min(0.55d, hits * 0.18d);
        }
        return Math.min(1d, score);
    }

    private String normalizeThemeCode(AssetUniverseEntity asset) {
        if (asset == null) {
            return null;
        }
        String raw = asset.getThemeCode() != null && !asset.getThemeCode().isBlank() ? asset.getThemeCode() : asset.getTheme();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (v.contains("AI") || v.contains("인공지능")) return "AI";
        if (v.contains("반도체") || v.contains("SEMICON") || v.contains("CHIP")) return "SEMICONDUCTOR";
        if (v.contains("방산") || v.contains("DEFENSE") || v.contains("군수")) return "DEFENSE";
        if (v.contains("우주") || v.contains("SPACE")) return "SPACE";
        if (v.contains("로봇") || v.contains("ROBOT")) return "ROBOTICS";
        if (v.contains("에너지") || v.contains("ENERGY") || v.contains("OIL") || v.contains("GAS")) return "ENERGY";
        if (v.contains("자원") || v.contains("RESOURCE") || v.contains("LITHIUM") || v.contains("GOLD")) return "RESOURCE";
        return v.replace(" ", "_");
    }

    private boolean containsToken(String normalizedText, String token) {
        if (normalizedText == null || token == null || token.isBlank()) {
            return false;
        }
        if (token.length() <= 2) {
            return normalizedText.equals(token)
                    || normalizedText.startsWith(token + " ")
                    || normalizedText.endsWith(" " + token)
                    || normalizedText.contains(" " + token + " ");
        }
        return normalizedText.contains(token);
    }

    private ScalpAggregation aggregateEvidence(
            AssetUniverseEntity asset,
            OffsetDateTime signalTime,
            List<ScalpEvidence> currentMapped,
            List<MarketPriceBarEntity> bars1m,
            List<MarketQuoteSnapshotEntity> quotesAsc,
            int previousMappedCount,
            int previousEligibleCount,
            int minNewsCount,
            BigDecimal positiveThreshold,
            BigDecimal negativeThreshold) {
        List<ScalpEvidence> eligible = currentMapped.stream().filter(ScalpEvidence::eligible).toList();
        int eligibleCount = eligible.size();
        boolean noMatchedNews = currentMapped.isEmpty();
        boolean insufficientSample = eligibleCount < Math.max(1, minNewsCount);

        double positiveMass = 0d;
        double negativeMass = 0d;
        double trustSum = 0d;
        double linkSum = 0d;
        double freshnessSum = 0d;
        double priceCoverageSum = 0d;
        double alignmentPenaltySum = 0d;
        Map<String, Double> posFactor = new HashMap<>();
        Map<String, Double> negFactor = new HashMap<>();
        List<Map<String, Object>> preview = new ArrayList<>();

        for (ScalpEvidence ev : eligible) {
            trustSum += ev.trust();
            linkSum += ev.linkConfidence();
            freshnessSum += ev.decayWeight();
            priceCoverageSum += ev.priceCoverage();
            alignmentPenaltySum += ev.alignmentPenaltyRate();

            double posThresholded = BigDecimal.valueOf(ev.positiveRatio()).compareTo(positiveThreshold) >= 0 ? ev.positiveRatio() : 0d;
            double negThresholded = BigDecimal.valueOf(ev.negativeRatio()).compareTo(negativeThreshold) >= 0 ? ev.negativeRatio() : 0d;
            double qualityWeight = ev.trust() * ev.linkConfidence() * ev.decayWeight() * ev.dedupWeight()
                    * (1d - ev.translationPenaltyRate()) * (1d - ev.alignmentPenaltyRate());
            double eventPosWeight = eventPositiveWeight(ev.eventType(), ev.impactDirection());
            double eventNegWeight = eventNegativeWeight(ev.eventType(), ev.impactDirection());
            double pricePosSupport = positivePriceSupport(ev);
            double priceNegSupport = negativePriceSupport(ev);
            double volumePosSupport = Math.max(0d, ev.volumeChangeRatio()) * 0.20d;
            double volumeNegSupport = Math.max(0d, -ev.volumeChangeRatio()) * 0.20d;

            double posContribution = qualityWeight * (posThresholded * (0.45d + eventPosWeight) + pricePosSupport + volumePosSupport);
            double negContribution = qualityWeight * (negThresholded * (0.45d + eventNegWeight) + priceNegSupport + volumeNegSupport);
            if ("POSITIVE".equals(ev.impactDirection())) posContribution += qualityWeight * 0.06d;
            if ("NEGATIVE".equals(ev.impactDirection())) negContribution += qualityWeight * 0.06d;

            positiveMass += Math.max(0d, posContribution);
            negativeMass += Math.max(0d, negContribution);

            addFactor(posFactor, "event_type_weight", qualityWeight * eventPosWeight);
            addFactor(negFactor, "event_type_weight", qualityWeight * eventNegWeight);
            addFactor(posFactor, "source_trust", qualityWeight * 0.18d);
            addFactor(negFactor, "source_trust", qualityWeight * 0.18d);
            addFactor(posFactor, "price_reaction", qualityWeight * pricePosSupport);
            addFactor(negFactor, "price_reaction", qualityWeight * priceNegSupport);
            addFactor(posFactor, "volume_change", qualityWeight * volumePosSupport);
            addFactor(negFactor, "volume_change", qualityWeight * volumeNegSupport);
            addFactor(negFactor, "translation_delay_penalty", ev.translationPenaltyRate() * 0.35d);
            addFactor(negFactor, "time_alignment_penalty", ev.alignmentPenaltyRate() * 0.40d);
            addFactor(negFactor, "dedup_penalty", (1d - ev.dedupWeight()) * 0.25d);

            if (preview.size() < 5) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("news_id", ev.news().getId());
                row.put("title", trim(ev.news().getTitleRaw(), 120));
                row.put("event_type", ev.eventType());
                row.put("impact_direction", ev.impactDirection());
                row.put("impact_horizon", ev.impactHorizon());
                row.put("trust_score", round6(ev.trust()));
                row.put("link_confidence", round6(ev.linkConfidence()));
                row.put("price_reaction_5m", round6(ev.priceReaction5m()));
                row.put("price_reaction_15m", round6(ev.priceReaction15m()));
                row.put("price_reaction_1h", round6(ev.priceReaction1h()));
                preview.add(row);
            }
        }

        double newsVolumeChangeRate = ratioDelta(currentMapped.size(), Math.max(1, previousMappedCount));
        double eligibleNewsChangeRate = ratioDelta(eligibleCount, Math.max(1, previousEligibleCount));
        addFactor(posFactor, "news_volume_change_rate", Math.max(0d, newsVolumeChangeRate) * 0.25d);
        addFactor(negFactor, "news_volume_change_rate", Math.max(0d, -newsVolumeChangeRate) * 0.25d);
        addFactor(posFactor, "eligible_news_change_rate", Math.max(0d, eligibleNewsChangeRate) * 0.25d);
        addFactor(negFactor, "eligible_news_change_rate", Math.max(0d, -eligibleNewsChangeRate) * 0.25d);

        double posNorm = normalizedMass(positiveMass);
        double negNorm = normalizedMass(negativeMass);
        double globalPenalty = Math.min(0.25d, insufficientSample ? 0.10d : 0d);
        BigDecimal goodProb;
        BigDecimal badProb;
        if (noMatchedNews || insufficientSample) {
            goodProb = BigDecimal.valueOf(0.5d);
            badProb = BigDecimal.valueOf(0.5d);
        } else {
            goodProb = SignalMath.clamp01(BigDecimal.valueOf(0.08d + (posNorm * 0.78d) - (negNorm * 0.12d)
                    + Math.max(0d, eligibleNewsChangeRate) * 0.05d - globalPenalty));
            badProb = SignalMath.clamp01(BigDecimal.valueOf(0.08d + (negNorm * 0.78d) - (posNorm * 0.12d)
                    + Math.max(0d, -eligibleNewsChangeRate) * 0.05d - globalPenalty));
        }

        double avgTrust = eligibleCount == 0 ? 0d : trustSum / eligibleCount;
        double avgLink = eligibleCount == 0 ? 0d : linkSum / eligibleCount;
        double avgFresh = eligibleCount == 0 ? 0d : freshnessSum / eligibleCount;
        double avgPriceCoverage = eligibleCount == 0 ? 0d : priceCoverageSum / eligibleCount;
        double avgAlignmentPenalty = eligibleCount == 0 ? 0d : alignmentPenaltySum / eligibleCount;
        double sampleRatio = Math.min(1d, (double) eligibleCount / Math.max(1, minNewsCount));
        BigDecimal confidenceFloor = normalizeRate(signalPolicyProperties.getInsufficientSampleConfidenceFloor(), BigDecimal.valueOf(0.2d));
        BigDecimal confidence = SignalMath.clamp01(BigDecimal.valueOf(
                (sampleRatio * 0.25d)
                        + (avgTrust * 0.20d)
                        + (avgLink * 0.20d)
                        + (avgFresh * 0.10d)
                        + (avgPriceCoverage * 0.10d)
                        + ((1d - avgAlignmentPenalty) * 0.15d)));
        if (insufficientSample || noMatchedNews) {
            confidence = SignalMath.clamp01(confidence.max(confidenceFloor).min(BigDecimal.valueOf(0.45d)));
        }
        BigDecimal scalpScore = SignalMath.clampScore(goodProb.subtract(badProb).multiply(confidence.max(BigDecimal.valueOf(0.35d))));

        String dataState = noMatchedNews ? "NO_MATCHED_NEWS" : (insufficientSample ? "INSUFFICIENT_DATA" : "SUFFICIENT_DATA");
        List<String> missingRequirements = buildMissingRequirements(noMatchedNews, insufficientSample, eligibleCount, minNewsCount, avgPriceCoverage);
        List<String> changeConditions = buildChangeConditions(dataState, minNewsCount, eligibleCount, avgPriceCoverage);
        String explainText = buildExplainText(asset, dataState, goodProb, badProb, confidence, eligibleCount, currentMapped.size(), posFactor, negFactor, missingRequirements);

        Map<String, Object> alignment = Map.of(
                "future_filtered_count", currentMapped.stream().filter(ScalpEvidence::futureFiltered).count(),
                "publish_fetch_order_warning_count", currentMapped.stream().filter(ScalpEvidence::invalidPublishFetchOrder).count(),
                "avg_alignment_penalty_rate", round6(avgAlignmentPenalty));
        Map<String, Object> freshness = Map.of(
                "latest_quote_age_minutes", latestQuoteAgeMinutes(signalTime, quotesAsc),
                "latest_bar_1m_age_minutes", latestBarAgeMinutes(signalTime, bars1m),
                "price_data_coverage_ratio", round6(avgPriceCoverage),
                "news_decay_freshness_ratio", round6(avgFresh));
        Map<String, Object> dedup = Map.of(
                "mapped_news_count", currentMapped.size(),
                "eligible_news_count", eligibleCount,
                "duplicate_article_penalty_applied", currentMapped.stream().anyMatch(e -> e.duplicateCount() > 1),
                "duplicate_news_count", currentMapped.stream().filter(e -> e.duplicateCount() > 1).count());

        return new ScalpAggregation(
                goodProb, badProb, confidence, scalpScore,
                eligibleCount, insufficientSample, dataState,
                positiveMass, negativeMass, posNorm, negNorm,
                newsVolumeChangeRate, eligibleNewsChangeRate,
                topFactorList(posFactor, true), topFactorList(negFactor, false),
                alignment, freshness, dedup, preview, missingRequirements, changeConditions, explainText);
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
        if (raw == null) {
            return "";
        }
        return raw
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

    private double eventPositiveWeight(String eventType, String impactDirection) {
        if ("NEGATIVE".equals(impactDirection)) {
            return 0.02d;
        }
        return switch (eventType) {
            case "EARNINGS" -> 0.22d;
            case "ORDER" -> 0.18d;
            case "REGULATION" -> 0.14d;
            case "SUPPLY_CHAIN" -> 0.10d;
            case "MACRO" -> 0.08d;
            case "INCIDENT" -> 0.01d;
            default -> 0.06d;
        };
    }

    private double eventNegativeWeight(String eventType, String impactDirection) {
        if ("POSITIVE".equals(impactDirection)) {
            return 0.02d;
        }
        return switch (eventType) {
            case "INCIDENT" -> 0.22d;
            case "REGULATION" -> 0.16d;
            case "EARNINGS" -> 0.14d;
            case "SUPPLY_CHAIN" -> 0.12d;
            case "MACRO" -> 0.10d;
            case "ORDER" -> 0.04d;
            default -> 0.06d;
        };
    }

    private double positivePriceSupport(ScalpEvidence ev) {
        double weighted = Math.max(0d, ev.priceReaction5m()) * 0.25d
                + Math.max(0d, ev.priceReaction15m()) * 0.35d
                + Math.max(0d, ev.priceReaction1h()) * 0.40d;
        return Math.min(0.35d, weighted * 4d);
    }

    private double negativePriceSupport(ScalpEvidence ev) {
        double weighted = Math.max(0d, -ev.priceReaction5m()) * 0.25d
                + Math.max(0d, -ev.priceReaction15m()) * 0.35d
                + Math.max(0d, -ev.priceReaction1h()) * 0.40d;
        return Math.min(0.35d, weighted * 4d);
    }

    private double normalizedMass(double mass) {
        return mass <= 0d ? 0d : (1d - Math.exp(-Math.min(8d, mass)));
    }

    private double ratioDelta(int current, int previousDenomSafe) {
        return ((double) current - previousDenomSafe) / Math.max(1d, previousDenomSafe);
    }

    private void addFactor(Map<String, Double> target, String key, double value) {
        if (value <= 0d) {
            return;
        }
        target.merge(key, value, Double::sum);
    }

    private List<Map<String, Object>> topFactorList(Map<String, Double> totals, boolean positive) {
        return totals.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0d)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .map(e -> Map.<String, Object>of(
                        "factor", e.getKey(),
                        "score", round6(e.getValue()),
                        "direction", positive ? "POSITIVE" : "NEGATIVE",
                        "description", factorDescription(e.getKey())))
                .toList();
    }

    private String factorDescription(String factor) {
        return switch (factor) {
            case "event_type_weight" -> "이벤트 유형 가중치";
            case "source_trust" -> "출처/신뢰도";
            case "price_reaction" -> "뉴스 후 가격반응";
            case "volume_change" -> "거래량 변화율";
            case "news_volume_change_rate" -> "뉴스량 변화율";
            case "eligible_news_change_rate" -> "유효 뉴스량 변화율";
            case "translation_delay_penalty" -> "번역 지연 패널티";
            case "time_alignment_penalty" -> "시간정렬 패널티";
            case "dedup_penalty" -> "중복기사 감점";
            default -> factor;
        };
    }

    private List<String> buildMissingRequirements(
            boolean noMatchedNews,
            boolean insufficientSample,
            int eligibleCount,
            int minNewsCount,
            double avgPriceCoverage) {
        List<String> list = new ArrayList<>();
        if (noMatchedNews) {
            list.add("뉴스-종목 직접/테마 매핑 근거가 부족합니다.");
        }
        if (insufficientSample) {
            list.add("고신뢰/시간정렬 통과 뉴스 표본이 부족합니다. (현재 " + eligibleCount + "건 / 최소 " + minNewsCount + "건)");
        }
        if (avgPriceCoverage < 0.5d) {
            list.add("뉴스 이후 가격/거래량 반응 데이터 커버리지가 낮습니다.");
        }
        return list;
    }

    private List<String> buildChangeConditions(String dataState, int minNewsCount, int eligibleCount, double avgPriceCoverage) {
        List<String> list = new ArrayList<>();
        if (!"SUFFICIENT_DATA".equals(dataState)) {
            list.add("고신뢰 매핑 뉴스가 최소 " + minNewsCount + "건 이상 확보되면 확률 해석력이 개선됩니다.");
        }
        if (avgPriceCoverage < 0.5d) {
            list.add("1분/15분/1시간 가격·거래량 반응 데이터가 추가 수집되면 판정이 변경될 수 있습니다.");
        }
        list.add("실적/수주/규제/사고 관련 신규 이벤트 뉴스 유입 시 확률이 재계산됩니다.");
        return list;
    }

    private String buildExplainText(
            AssetUniverseEntity asset,
            String dataState,
            BigDecimal goodProb,
            BigDecimal badProb,
            BigDecimal confidence,
            int eligibleCount,
            int mappedCount,
            Map<String, Double> posFactor,
            Map<String, Double> negFactor,
            List<String> missingRequirements) {
        String assetName = asset == null ? "-" : (asset.getAssetName() == null ? asset.getAssetCode() : asset.getAssetName());
        if ("NO_MATCHED_NEWS".equals(dataState)) {
            return assetName + " 관련 매핑 뉴스가 부족하여 뉴스기반 확률은 보류 상태입니다. 현재 매핑 " + mappedCount + "건입니다.";
        }
        if ("INSUFFICIENT_DATA".equals(dataState)) {
            return assetName + " 뉴스 표본 부족으로 중립/보류 상태입니다. 유효 뉴스 "
                    + eligibleCount + "건, confidence " + confidence.setScale(3, RoundingMode.HALF_UP)
                    + (missingRequirements.isEmpty() ? "" : " · " + missingRequirements.get(0));
        }
        return assetName + " 뉴스 근거 기반 결과: 호재 "
                + goodProb.setScale(3, RoundingMode.HALF_UP)
                + ", 악재 " + badProb.setScale(3, RoundingMode.HALF_UP)
                + ", confidence " + confidence.setScale(3, RoundingMode.HALF_UP)
                + ". 주요 긍정요인=" + topFactorName(posFactor) + ", 주요 부정요인=" + topFactorName(negFactor) + ".";
    }

    private String topFactorName(Map<String, Double> factors) {
        return factors.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> factorDescription(e.getKey()))
                .orElse("없음");
    }

    private long translationDelaySeconds(NewsEntity news) {
        if (news == null || news.getTranslatedAtUtc() == null) {
            return 0L;
        }
        OffsetDateTime base = firstNonNull(news.getPublishedAtUtc(), news.getPubUtc(), news.getFetchedAtUtc(), news.getFetchUtc());
        if (base == null) {
            return 0L;
        }
        return Math.max(0L, ChronoUnit.SECONDS.between(base, news.getTranslatedAtUtc()));
    }

    private long latestQuoteAgeMinutes(OffsetDateTime signalTime, List<MarketQuoteSnapshotEntity> quotesAsc) {
        if (signalTime == null || quotesAsc == null || quotesAsc.isEmpty()) {
            return -1L;
        }
        OffsetDateTime latest = quoteTime(quotesAsc.get(quotesAsc.size() - 1));
        if (latest == null) {
            return -1L;
        }
        return Math.max(0L, ChronoUnit.MINUTES.between(latest, signalTime));
    }

    private long latestBarAgeMinutes(OffsetDateTime signalTime, List<MarketPriceBarEntity> barsAsc) {
        if (signalTime == null || barsAsc == null || barsAsc.isEmpty()) {
            return -1L;
        }
        OffsetDateTime latest = barTime(barsAsc.get(barsAsc.size() - 1));
        if (latest == null) {
            return -1L;
        }
        return Math.max(0L, ChronoUnit.MINUTES.between(latest, signalTime));
    }

    private String trim(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        return normalized.length() > maxLen ? normalized.substring(0, maxLen) : normalized;
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }

    private String toJson(Object map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private record LinkEvaluation(
            NewsLinkType linkType,
            BigDecimal linkConfidence,
            String linkMethod,
            Map<String, Object> keywordHits,
            String tickerAliasHit,
            BigDecimal themeMatchScore) {
    }

    private record ScalpEvidence(
            NewsEntity news,
            OffsetDateTime eventTime,
            double linkConfidence,
            String linkMethod,
            String tickerAliasHit,
            double themeMatchScore,
            int posHits,
            int negHits,
            double positiveRatio,
            double negativeRatio,
            double trust,
            int duplicateCount,
            double dedupWeight,
            long translationDelaySeconds,
            double translationPenaltyRate,
            boolean futureFiltered,
            boolean trustFiltered,
            boolean invalidPublishFetchOrder,
            double decayWeight,
            String eventType,
            String impactDirection,
            String impactHorizon,
            double priceReaction5m,
            double priceReaction15m,
            double priceReaction1h,
            double volumeChangeRatio,
            double priceCoverage) {
        boolean eligible() {
            return !futureFiltered && !trustFiltered;
        }

        boolean hasStrongSentiment(BigDecimal positiveThreshold, BigDecimal negativeThreshold) {
            return BigDecimal.valueOf(positiveRatio).compareTo(positiveThreshold) >= 0
                    || BigDecimal.valueOf(negativeRatio).compareTo(negativeThreshold) >= 0;
        }

        double alignmentPenaltyRate() {
            double p = (futureFiltered ? 0.30d : 0d) + (invalidPublishFetchOrder ? 0.12d : 0d);
            return Math.min(0.60d, p);
        }
    }

    private record ScalpAggregation(
            BigDecimal goodProb,
            BigDecimal badProb,
            BigDecimal confidence,
            BigDecimal scalpScore,
            int eligibleCount,
            boolean insufficientSample,
            String dataState,
            double positiveMass,
            double negativeMass,
            double positiveMassNormalized,
            double negativeMassNormalized,
            double newsVolumeChangeRate,
            double eligibleNewsChangeRate,
            List<Map<String, Object>> topPositiveFactors,
            List<Map<String, Object>> topNegativeFactors,
            Map<String, Object> newsAlignmentResult,
            Map<String, Object> dataFreshness,
            Map<String, Object> dedupResult,
            List<Map<String, Object>> mappedNewsPreview,
            List<String> missingRequirements,
            List<String> changeConditions,
            String explainText) {
    }
}

package com.wangbyul.gnd.api.service;

import com.wangbyul.gnd.api.dto.StockSignalDto;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.domain.TickerAliasDictionaryEntity;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.NewsRepository;
import com.wangbyul.gnd.core.repository.TickerAliasDictionaryRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 뉴스 기반 주식 시그널(규칙형) 서비스.
 *
 * 고정 종목 하드코딩을 제거하고, DB 자산 유니버스 + alias 사전을 기준으로 동적 계산한다.
 */
@Service
public class StockSignalService {

    private static final List<String> POSITIVE_WORDS = List.of(
            "surge", "rise", "rally", "beat", "growth", "record", "upgrade", "expand", "strong",
            "상승", "급등", "호재", "개선", "증가", "확대", "반등", "사상 최대", "성장");

    private static final List<String> NEGATIVE_WORDS = List.of(
            "fall", "drop", "slump", "miss", "downgrade", "recession", "cut", "weak", "risk",
            "하락", "급락", "악재", "감소", "둔화", "침체", "위기", "리스크", "손실");

    private final NewsRepository newsRepository;
    private final AssetUniverseRepository assetUniverseRepository;
    private final TickerAliasDictionaryRepository tickerAliasDictionaryRepository;

    public StockSignalService(
            NewsRepository newsRepository,
            AssetUniverseRepository assetUniverseRepository,
            TickerAliasDictionaryRepository tickerAliasDictionaryRepository) {
        this.newsRepository = newsRepository;
        this.assetUniverseRepository = assetUniverseRepository;
        this.tickerAliasDictionaryRepository = tickerAliasDictionaryRepository;
    }

    public List<StockSignalDto> buildSignals(String country, String period, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10));
        OffsetDateTime since = resolveSince(period);
        List<NewsEntity> recent = newsRepository.findTop200ByCountryOrderByPubUtcDesc(country).stream()
                .filter(news -> news.getPubUtc() != null && news.getPubUtc().isAfter(since))
                .toList();
        if (recent.isEmpty()) {
            return List.of();
        }

        List<AssetUniverseEntity> candidates = resolveCandidates(country, safeLimit * 12);
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<String, List<String>> aliasMap = buildAliasMap(candidates);
        List<String> normalizedNews = recent.stream()
                .map(news -> normalize(joinText(news.getTitleRaw(), news.getSummaryKo(), news.getBodyRaw())))
                .toList();

        List<StockSignalDto> signals = new ArrayList<>();
        for (AssetUniverseEntity asset : candidates) {
            List<String> aliases = aliasMap.getOrDefault(asset.getAssetCode(), List.of());
            if (aliases.isEmpty()) {
                continue;
            }

            int matchedArticles = 0;
            int relevance = 0;
            int sentiment = 0;
            for (String text : normalizedNews) {
                int hit = aliasHit(text, aliases);
                if (hit <= 0) {
                    continue;
                }
                matchedArticles++;
                relevance += hit;
                sentiment += sentimentScore(text);
            }
            if (matchedArticles == 0) {
                continue;
            }

            int upProbability = computeUpProbability(relevance, sentiment);
            int downProbability = 100 - upProbability;
            int confidence = (int) clamp(35 + relevance * 6 + Math.abs(sentiment) * 4 + matchedArticles * 2, 35, 95);
            String reason = "연관 기사 " + matchedArticles + "건, 키워드 점수 " + relevance + ", 감성 점수 " + sentiment;

            signals.add(StockSignalDto.builder()
                    .stockCode(asset.getAssetCode())
                    .stockName(asset.getAssetName())
                    .upProbability(upProbability)
                    .downProbability(downProbability)
                    .confidence(confidence)
                    .matchedArticles(matchedArticles)
                    .reason(reason)
                    .build());
        }

        return signals.stream()
                .sorted((a, b) -> {
                    int cmpConfidence = Integer.compare(b.getConfidence(), a.getConfidence());
                    if (cmpConfidence != 0) {
                        return cmpConfidence;
                    }
                    int cmpMatched = Integer.compare(b.getMatchedArticles(), a.getMatchedArticles());
                    if (cmpMatched != 0) {
                        return cmpMatched;
                    }
                    return Integer.compare(b.getUpProbability(), a.getUpProbability());
                })
                .limit(safeLimit)
                .toList();
    }

    private List<AssetUniverseEntity> resolveCandidates(String country, int maxCandidates) {
        List<AssetUniverseEntity> rows = assetUniverseRepository
                .findByCountryAndActiveTrueOrderByDisplayWeightDescSelectionScoreDescUpdatedAtDesc(country);
        return rows.stream()
                .filter(asset -> asset.getAssetCode() != null && !asset.getAssetCode().isBlank())
                .limit(Math.max(1, Math.min(maxCandidates, 200)))
                .toList();
    }

    private Map<String, List<String>> buildAliasMap(List<AssetUniverseEntity> assets) {
        Map<String, List<TickerAliasDictionaryEntity>> byAsset = tickerAliasDictionaryRepository
                .findByActiveTrueOrderByAliasValueAsc()
                .stream()
                .collect(Collectors.groupingBy(TickerAliasDictionaryEntity::getAssetCode));

        Map<String, List<String>> map = new LinkedHashMap<>();
        for (AssetUniverseEntity asset : assets) {
            LinkedHashSet<String> aliases = new LinkedHashSet<>();
            addAlias(aliases, asset.getAssetCode());
            addAlias(aliases, tickerBase(asset.getAssetCode()));
            addAlias(aliases, asset.getAssetName());
            addAlias(aliases, asset.getTheme());
            addAlias(aliases, asset.getSector());
            for (TickerAliasDictionaryEntity alias : byAsset.getOrDefault(asset.getAssetCode(), List.of())) {
                addAlias(aliases, alias.getAliasValue());
            }
            map.put(asset.getAssetCode(), new ArrayList<>(aliases));
        }
        return map;
    }

    private String tickerBase(String assetCode) {
        if (assetCode == null || assetCode.isBlank()) {
            return null;
        }
        String code = assetCode.trim().toUpperCase(Locale.ROOT);
        int dot = code.indexOf('.');
        return dot > 0 ? code.substring(0, dot) : code;
    }

    private void addAlias(Set<String> aliases, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String normalized = normalize(raw);
        if (normalized.isBlank()) {
            return;
        }
        if (normalized.length() <= 2 && !normalized.matches("\\d{4,10}")) {
            return;
        }
        aliases.add(normalized);
    }

    private int aliasHit(String normalizedText, List<String> aliases) {
        if (normalizedText == null || normalizedText.isBlank() || aliases == null || aliases.isEmpty()) {
            return 0;
        }
        int hit = 0;
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            if (normalizedText.contains(alias)) {
                hit++;
            }
        }
        return hit;
    }

    private OffsetDateTime resolveSince(String period) {
        OffsetDateTime now = OffsetDateTime.now();
        return switch (period) {
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            default -> now.minusHours(24);
        };
    }

    private int computeUpProbability(int relevance, int sentiment) {
        double raw = 50 + (relevance * 2.2) + (sentiment * 5.4);
        return (int) Math.round(clamp(raw, 5, 95));
    }

    private int sentimentScore(String normalizedText) {
        int positive = 0;
        int negative = 0;
        for (String keyword : POSITIVE_WORDS) {
            if (normalizedText.contains(keyword)) {
                positive++;
            }
        }
        for (String keyword : NEGATIVE_WORDS) {
            if (normalizedText.contains(keyword)) {
                negative++;
            }
        }
        return positive - negative;
    }

    private String joinText(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            builder.append(' ').append(value);
        }
        return builder.toString();
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replaceAll("[^\\p{L}\\p{N} ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private double clamp(double value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

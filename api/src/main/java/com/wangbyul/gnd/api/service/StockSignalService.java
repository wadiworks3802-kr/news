package com.wangbyul.gnd.api.service;

import com.wangbyul.gnd.api.dto.StockSignalDto;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.repository.NewsRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 뉴스 기반 주식 시그널(규칙형) 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 주의:
 * - 투자 자문이 아닌 개인 참고용 휴리스틱 지표이다.
 * - 국가별 대표 종목 후보와 뉴스 키워드 매칭으로 상승/하락 확률을 단순 계산한다.
 */
@Service
public class StockSignalService {

    private static final List<String> POSITIVE_WORDS = List.of(
            "surge", "rise", "rally", "beat", "growth", "record", "upgrade", "expand", "strong",
            "상승", "급등", "호재", "개선", "증가", "확대", "반등", "사상 최대", "성장");

    private static final List<String> NEGATIVE_WORDS = List.of(
            "fall", "drop", "slump", "miss", "downgrade", "recession", "cut", "weak", "risk",
            "하락", "급락", "악재", "감소", "둔화", "침체", "위기", "리스크", "손실");

    private static final Map<String, List<StockTarget>> COUNTRY_TARGETS = Map.of(
            "KR", List.of(
                    new StockTarget("005930.KS", "Samsung Electronics", List.of("semiconductor", "chip", "memory", "반도체", "메모리")),
                    new StockTarget("000660.KS", "SK Hynix", List.of("hbm", "ai chip", "dram", "낸드", "hynix")),
                    new StockTarget("005380.KS", "Hyundai Motor", List.of("auto", "ev", "car", "전기차", "자동차"))),
            "US", List.of(
                    new StockTarget("NVDA", "NVIDIA", List.of("ai", "gpu", "datacenter", "chip", "semiconductor")),
                    new StockTarget("AAPL", "Apple", List.of("iphone", "ios", "apple", "device")),
                    new StockTarget("TSLA", "Tesla", List.of("ev", "tesla", "battery", "autonomous"))),
            "JP", List.of(
                    new StockTarget("7203.T", "Toyota", List.of("toyota", "auto", "hybrid", "ev")),
                    new StockTarget("6758.T", "Sony", List.of("sony", "playstation", "sensor")),
                    new StockTarget("9984.T", "SoftBank", List.of("softbank", "telecom", "investment"))),
            "DE", List.of(
                    new StockTarget("SAP.DE", "SAP", List.of("sap", "enterprise software", "cloud")),
                    new StockTarget("VOW3.DE", "Volkswagen", List.of("volkswagen", "auto", "ev")),
                    new StockTarget("SIE.DE", "Siemens", List.of("industrial", "factory", "automation"))),
            "FR", List.of(
                    new StockTarget("MC.PA", "LVMH", List.of("luxury", "lvmh", "consumer")),
                    new StockTarget("OR.PA", "L'Oreal", List.of("beauty", "cosmetics", "consumer")),
                    new StockTarget("AIR.PA", "Airbus", List.of("airbus", "aviation", "aircraft"))),
            "UK", List.of(
                    new StockTarget("SHEL.L", "Shell", List.of("oil", "gas", "energy", "lng")),
                    new StockTarget("HSBA.L", "HSBC", List.of("bank", "finance", "rate")),
                    new StockTarget("AZN.L", "AstraZeneca", List.of("pharma", "drug", "healthcare"))),
            "CN", List.of(
                    new StockTarget("0700.HK", "Tencent", List.of("tencent", "game", "internet")),
                    new StockTarget("9988.HK", "Alibaba", List.of("alibaba", "ecommerce", "cloud")),
                    new StockTarget("1211.HK", "BYD", List.of("byd", "ev", "battery"))),
            "IN", List.of(
                    new StockTarget("RELIANCE.NS", "Reliance", List.of("energy", "telecom", "retail")),
                    new StockTarget("TCS.NS", "TCS", List.of("it service", "software", "outsourcing")),
                    new StockTarget("HDFCBANK.NS", "HDFC Bank", List.of("bank", "finance", "lending"))),
            "RU", List.of(
                    new StockTarget("GAZP.ME", "Gazprom", List.of("gas", "pipeline", "energy")),
                    new StockTarget("SBER.ME", "Sberbank", List.of("bank", "finance", "credit")),
                    new StockTarget("LKOH.ME", "Lukoil", List.of("oil", "refining", "energy"))),
            "BR", List.of(
                    new StockTarget("PETR4.SA", "Petrobras", List.of("oil", "energy", "offshore")),
                    new StockTarget("VALE3.SA", "Vale", List.of("iron ore", "mining", "commodity")),
                    new StockTarget("ITUB4.SA", "Itau Unibanco", List.of("bank", "finance", "credit"))));

    private final NewsRepository newsRepository;

    public StockSignalService(NewsRepository newsRepository) {
        this.newsRepository = newsRepository;
    }

    public List<StockSignalDto> buildSignals(String country, String period, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10));
        OffsetDateTime since = resolveSince(period);
        List<NewsEntity> recent = newsRepository.findTop200ByCountryOrderByPubUtcDesc(country).stream()
                .filter(news -> news.getPubUtc() != null && news.getPubUtc().isAfter(since))
                .toList();

        List<StockTarget> targets = COUNTRY_TARGETS.getOrDefault(country, COUNTRY_TARGETS.get("US"));
        List<StockSignalDto> signals = new ArrayList<>();

        for (StockTarget target : targets) {
            int matchedArticles = 0;
            int relevance = 0;
            int sentiment = 0;

            for (NewsEntity news : recent) {
                String text = normalize(joinText(news.getTitleRaw(), news.getSummaryKo(), news.getBodyRaw()));
                int hit = keywordHit(text, target.keywords());
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
            int confidence = (int) clamp(35 + relevance * 7 + Math.abs(sentiment) * 4, 35, 95);

            String reason = "연관 기사 " + matchedArticles + "건, 키워드 점수 " + relevance + ", 감성 점수 " + sentiment;
            signals.add(StockSignalDto.builder()
                    .stockCode(target.code())
                    .stockName(target.name())
                    .upProbability(upProbability)
                    .downProbability(downProbability)
                    .confidence(confidence)
                    .matchedArticles(matchedArticles)
                    .reason(reason)
                    .build());
        }

        if (signals.isEmpty()) {
            return targets.stream()
                    .limit(safeLimit)
                    .map(target -> StockSignalDto.builder()
                            .stockCode(target.code())
                            .stockName(target.name())
                            .upProbability(50)
                            .downProbability(50)
                            .confidence(30)
                            .matchedArticles(0)
                            .reason("연관 기사 부족으로 중립(50/50) 처리")
                            .build())
                    .toList();
        }

        return signals.stream()
                .sorted((a, b) -> {
                    int cmpConfidence = Integer.compare(b.getConfidence(), a.getConfidence());
                    if (cmpConfidence != 0) {
                        return cmpConfidence;
                    }
                    return Integer.compare(b.getUpProbability(), a.getUpProbability());
                })
                .limit(safeLimit)
                .toList();
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

    private int keywordHit(String normalizedText, List<String> keywords) {
        int hit = 0;
        for (String keyword : keywords) {
            if (normalizedText.contains(keyword.toLowerCase(Locale.ROOT))) {
                hit++;
            }
        }
        return hit;
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
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&[a-zA-Z#0-9]+;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private double clamp(double value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record StockTarget(String code, String name, List<String> keywords) {
    }
}

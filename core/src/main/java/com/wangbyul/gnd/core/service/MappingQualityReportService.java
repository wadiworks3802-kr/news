package com.wangbyul.gnd.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.MappingQualityReportEntity;
import com.wangbyul.gnd.core.domain.NewsAssetLinkEntity;
import com.wangbyul.gnd.core.domain.NewsLinkType;
import com.wangbyul.gnd.core.repository.AssetUniverseRepository;
import com.wangbyul.gnd.core.repository.MappingQualityReportRepository;
import com.wangbyul.gnd.core.repository.NewsAssetLinkRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 뉴스-자산 매핑 품질 리포트 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
@RequiredArgsConstructor
public class MappingQualityReportService {

    private final NewsAssetLinkRepository newsAssetLinkRepository;
    private final MappingQualityReportRepository mappingQualityReportRepository;
    private final AssetUniverseRepository assetUniverseRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.universe.mapping-window-days:7}")
    private int mappingWindowDays;

    @Value("${app.universe.mapping-max-links-per-news:6}")
    private int mappingMaxLinksPerNews;

    @Transactional
    public MappingQualityReportEntity generateReport(String country, String theme, String triggeredBy) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(Math.max(1, mappingWindowDays));
        List<NewsAssetLinkEntity> links = newsAssetLinkRepository.findTop5000ByCreatedAtAfterOrderByCreatedAtDesc(since);
        List<NewsAssetLinkEntity> scopedLinks = filterByScope(links, country, theme);

        int sampleSize = scopedLinks.size();
        List<NewsAssetLinkEntity> directLinks = scopedLinks.stream()
                .filter(link -> link.getLinkType() == NewsLinkType.DIRECT)
                .toList();
        List<NewsAssetLinkEntity> themeLinks = scopedLinks.stream()
                .filter(link -> link.getLinkType() == NewsLinkType.THEME)
                .toList();

        BigDecimal directPrecision = ratio(
                directLinks.stream().filter(link -> safeDecimal(link.getConfidence()).compareTo(BigDecimal.valueOf(0.6d)) >= 0).count(),
                Math.max(1, directLinks.size()));
        BigDecimal themeFalsePositiveRate = ratio(
                themeLinks.stream().filter(link -> safeDecimal(link.getConfidence()).compareTo(BigDecimal.valueOf(0.4d)) < 0).count(),
                Math.max(1, themeLinks.size()));

        Map<String, Long> linksPerNews = scopedLinks.stream()
                .collect(Collectors.groupingBy(NewsAssetLinkEntity::getNewsId, Collectors.counting()));
        long overExpandedNews = linksPerNews.values().stream()
                .filter(count -> count > Math.max(1, mappingMaxLinksPerNews))
                .count();
        BigDecimal overExpansionRate = ratio(overExpandedNews, Math.max(1, linksPerNews.size()));

        List<BigDecimal> scores = scopedLinks.stream()
                .map(link -> safeDecimal(link.getConfidence()))
                .sorted()
                .toList();
        BigDecimal scoreAvg = scores.isEmpty()
                ? BigDecimal.ZERO
                : scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(scores.size()), 6, RoundingMode.HALF_UP);
        BigDecimal p50 = percentile(scores, 0.5d);
        BigDecimal p90 = percentile(scores, 0.9d);

        MappingQualityReportEntity report = new MappingQualityReportEntity();
        report.setCountry(normalizeScope(country));
        report.setTheme(normalizeScope(theme));
        report.setSampleSize(sampleSize);
        report.setDirectMatchPrecision(directPrecision);
        report.setThemeMatchFalsePositiveRate(themeFalsePositiveRate);
        report.setCountryThemeOverexpansionRate(overExpansionRate);
        report.setLinkScoreAvg(scoreAvg);
        report.setLinkScoreP50(p50);
        report.setLinkScoreP90(p90);
        report.setSummaryJson(toJson(Map.of(
                "triggered_by", triggeredBy == null ? "system" : triggeredBy,
                "window_days", Math.max(1, mappingWindowDays),
                "max_links_per_news", Math.max(1, mappingMaxLinksPerNews),
                "direct_links", directLinks.size(),
                "theme_links", themeLinks.size(),
                "news_group_count", linksPerNews.size(),
                "overexpanded_news_count", overExpandedNews)));
        report.setTraceId(traceId());
        return mappingQualityReportRepository.save(report);
    }

    @Transactional
    public Map<String, Object> diagnostics(String country, String theme, boolean refresh) {
        MappingQualityReportEntity latest = null;
        if (refresh) {
            latest = generateReport(country, theme, "diagnostics-api");
        } else if (country != null && !country.isBlank() && theme != null && !theme.isBlank()) {
            latest = mappingQualityReportRepository.findTop1ByCountryAndThemeOrderByReportTimeUtcDesc(
                            normalizeScope(country),
                            normalizeScope(theme))
                    .orElse(null);
        }
        if (latest == null) {
            latest = mappingQualityReportRepository.findTop200ByOrderByReportTimeUtcDesc().stream().findFirst().orElse(null);
        }
        if (latest == null) {
            latest = generateReport(country, theme, "diagnostics-api-bootstrap");
        }

        List<MappingQualityReportEntity> recent = mappingQualityReportRepository.findTop200ByOrderByReportTimeUtcDesc().stream()
                .limit(30)
                .toList();
        List<Map<String, Object>> trend = new ArrayList<>();
        for (MappingQualityReportEntity row : recent) {
            trend.add(Map.of(
                    "report_time_utc", row.getReportTimeUtc(),
                    "country", row.getCountry() == null ? "ALL" : row.getCountry(),
                    "theme", row.getTheme() == null ? "ALL" : row.getTheme(),
                    "direct_match_precision", safeDecimal(row.getDirectMatchPrecision()),
                    "theme_match_false_positive_rate", safeDecimal(row.getThemeMatchFalsePositiveRate()),
                    "overexpansion_rate", safeDecimal(row.getCountryThemeOverexpansionRate())));
        }

        return Map.of(
                "latest", Map.ofEntries(
                        Map.entry("id", latest.getId()),
                        Map.entry("report_time_utc", latest.getReportTimeUtc()),
                        Map.entry("country", latest.getCountry()),
                        Map.entry("theme", latest.getTheme()),
                        Map.entry("sample_size", latest.getSampleSize()),
                        Map.entry("direct_match_precision", latest.getDirectMatchPrecision()),
                        Map.entry("theme_match_false_positive_rate", latest.getThemeMatchFalsePositiveRate()),
                        Map.entry("country_theme_overexpansion_rate", latest.getCountryThemeOverexpansionRate()),
                        Map.entry("link_score_avg", latest.getLinkScoreAvg()),
                        Map.entry("link_score_p50", latest.getLinkScoreP50()),
                        Map.entry("link_score_p90", latest.getLinkScoreP90()),
                        Map.entry("summary_json", latest.getSummaryJson())),
                "trend", trend);
    }

    private List<NewsAssetLinkEntity> filterByScope(List<NewsAssetLinkEntity> links, String country, String theme) {
        Map<String, AssetUniverseEntity> assetMap = assetUniverseRepository.findAll().stream()
                .collect(Collectors.toMap(AssetUniverseEntity::getAssetCode, asset -> asset, (a, b) -> a));
        String scopeCountry = normalizeScope(country);
        String scopeTheme = normalizeScope(theme);

        return links.stream().filter(link -> {
            AssetUniverseEntity asset = assetMap.get(link.getAssetCode());
            if (asset == null) {
                return false;
            }
            boolean countryOk = scopeCountry == null || scopeCountry.equalsIgnoreCase(asset.getCountry());
            boolean themeOk = scopeTheme == null || scopeTheme.equalsIgnoreCase(asset.getTheme());
            return countryOk && themeOk;
        }).toList();
    }

    private BigDecimal percentile(List<BigDecimal> values, double pct) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int index = Math.max(0, Math.min(values.size() - 1, (int) Math.floor((values.size() - 1) * pct)));
        return values.get(index).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(long numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(Math.max(0L, numerator))
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal safeDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP) : value.setScale(6, RoundingMode.HALF_UP);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String normalizeScope(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) {
            return null;
        }
        return value.trim();
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }
}

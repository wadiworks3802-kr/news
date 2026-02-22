package com.wangbyul.gnd.api.service.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.AssistantRagProperties;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
import com.wangbyul.gnd.core.domain.NewsAssetLinkEntity;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.domain.SignalAuditLogEntity;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.repository.MarketPriceBarRepository;
import com.wangbyul.gnd.core.repository.MarketQuoteSnapshotRepository;
import com.wangbyul.gnd.core.repository.NewsAssetLinkRepository;
import com.wangbyul.gnd.core.repository.NewsRepository;
import com.wangbyul.gnd.core.repository.SignalAuditLogRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 경량 RAG 보조 분석용 컨텍스트 빌더.
 *
 * 뉴스-종목 매핑 결과 + 시세/바 + 시그널/감사로그 일부를 조합해
 * 모델 입력 컨텍스트와 참조 목록(`rag_context_refs_json`)을 생성한다.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class AssistantRagContextBuilderService {

    private final NewsAssetLinkRepository newsAssetLinkRepository;
    private final NewsRepository newsRepository;
    private final MarketQuoteSnapshotRepository marketQuoteSnapshotRepository;
    private final MarketPriceBarRepository marketPriceBarRepository;
    private final SignalAuditLogRepository signalAuditLogRepository;
    private final AssistantRagProperties properties;
    private final ObjectMapper objectMapper;

    public AssistantRagContextBuilderService(
            NewsAssetLinkRepository newsAssetLinkRepository,
            NewsRepository newsRepository,
            MarketQuoteSnapshotRepository marketQuoteSnapshotRepository,
            MarketPriceBarRepository marketPriceBarRepository,
            SignalAuditLogRepository signalAuditLogRepository,
            AssistantRagProperties properties,
            ObjectMapper objectMapper) {
        this.newsAssetLinkRepository = newsAssetLinkRepository;
        this.newsRepository = newsRepository;
        this.marketQuoteSnapshotRepository = marketQuoteSnapshotRepository;
        this.marketPriceBarRepository = marketPriceBarRepository;
        this.signalAuditLogRepository = signalAuditLogRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public SignalDetailRagContext buildSignalDetailContext(
            TradingSignalEntity signal,
            AssetUniverseEntity asset,
            List<String> riskChecks,
            Map<String, Object> scalpBreakdown) {
        String assetCode = signal == null ? null : signal.getAssetCode();
        OffsetDateTime since = OffsetDateTime.now().minusHours(Math.max(1, properties.getNewsLookbackHours()));

        List<NewsAssetLinkEntity> links = assetCode == null
                ? List.of()
                : newsAssetLinkRepository.findTop500ByAssetCodeAndCreatedAtAfterOrderByCreatedAtDesc(assetCode, since);
        List<NewsAssetLinkEntity> selectedLinks = links.stream()
                .sorted(Comparator.comparing(NewsAssetLinkEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, properties.getMaxRecentNews() * 3L))
                .toList();

        Set<String> newsIds = new LinkedHashSet<>();
        for (NewsAssetLinkEntity link : selectedLinks) {
            if (link.getNewsId() != null && !link.getNewsId().isBlank()) {
                newsIds.add(link.getNewsId());
            }
        }

        Map<String, NewsEntity> newsMap = new LinkedHashMap<>();
        if (!newsIds.isEmpty()) {
            for (NewsEntity news : newsRepository.findAllById(newsIds)) {
                newsMap.put(news.getId(), news);
            }
        }

        List<Map<String, Object>> recentNews = new ArrayList<>();
        List<Map<String, Object>> refs = new ArrayList<>();
        Set<String> addedNewsRefIds = new LinkedHashSet<>();
        for (NewsAssetLinkEntity link : selectedLinks) {
            NewsEntity news = newsMap.get(link.getNewsId());
            if (news == null) {
                continue;
            }
            if (recentNews.size() >= Math.max(1, properties.getMaxRecentNews())) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("news_id", news.getId());
            item.put("title", firstNonBlank(news.getTitleKo(), news.getTitleRaw(), "(제목 없음)"));
            item.put("summary", firstNonBlank(news.getSummaryKo(), abbreviate(news.getBodyRaw(), 220), "(요약 없음)"));
            item.put("category", news.getCategory() == null ? "N/A" : news.getCategory().name());
            item.put("trust_score", scale(news.getTrustScore(), 4));
            item.put("published_at_utc", news.getPublishedAtUtc() != null ? news.getPublishedAtUtc() : news.getPubUtc());
            item.put("source_sid", "");
            item.put("link_confidence", scale(firstNonNull(link.getLinkConfidence(), link.getConfidence(), BigDecimal.ZERO), 4));
            item.put("link_method", blankAs(link.getLinkMethod(), "RULE"));
            item.put("event_type", blankAs(link.getEventType(), "UNKNOWN"));
            item.put("impact_direction", blankAs(link.getImpactDirection(), "NEUTRAL"));
            item.put("impact_horizon", blankAs(link.getImpactHorizon(), "N/A"));
            item.put("ticker_alias_hit", blankAs(link.getTickerAliasHit(), ""));
            recentNews.add(item);

            if (addedNewsRefIds.add(news.getId())) {
                refs.add(Map.of(
                        "ref_type", "news",
                        "id", news.getId(),
                        "published_at_utc", String.valueOf(item.get("published_at_utc")),
                        "event_type", item.get("event_type"),
                        "impact_direction", item.get("impact_direction")));
            }
        }

        List<MarketQuoteSnapshotEntity> quoteRows = assetCode == null
                ? List.of()
                : marketQuoteSnapshotRepository.findTop2ByAssetCodeOrderBySnapshotUtcDesc(assetCode);
        Map<String, Object> quoteSummary = buildQuoteSummary(quoteRows);
        if (!quoteRows.isEmpty()) {
            MarketQuoteSnapshotEntity latest = quoteRows.get(0);
            refs.add(Map.of(
                    "ref_type", "quote",
                    "asset_code", blankAs(latest.getAssetCode(), ""),
                    "snapshot_utc", String.valueOf(latest.getSnapshotUtc()),
                    "provider", blankAs(latest.getProviderName(), "")));
        }

        List<MarketPriceBarEntity> bars = assetCode == null
                ? List.of()
                : marketPriceBarRepository.findTop240ByAssetCodeAndTimeframeOrderByBarTimeDesc(
                        assetCode,
                        blankAs(properties.getBarTimeframe(), "1m"));
        List<MarketPriceBarEntity> selectedBars = bars.stream()
                .limit(Math.max(1, properties.getMaxBars()))
                .toList();
        Map<String, Object> barSummary = buildBarSummary(selectedBars, blankAs(properties.getBarTimeframe(), "1m"));
        if (!selectedBars.isEmpty()) {
            MarketPriceBarEntity latestBar = selectedBars.get(0);
            refs.add(Map.of(
                    "ref_type", "bar",
                    "asset_code", blankAs(latestBar.getAssetCode(), ""),
                    "timeframe", blankAs(latestBar.getTimeframe(), ""),
                    "bar_time_utc", String.valueOf(firstNonNull(latestBar.getBarTimeUtc(), latestBar.getBarTime(), null))));
        }

        List<SignalAuditLogEntity> auditRows = signal == null || signal.getId() == null
                ? List.of()
                : signalAuditLogRepository.findTop200BySignalIdOrderByAuditTimeUtcDesc(signal.getId());
        List<Map<String, Object>> signalAudits = auditRows.stream()
                .limit(Math.max(1, properties.getMaxSignalAudits()))
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", row.getId());
                    item.put("engine_type", row.getEngineType() == null ? "N/A" : row.getEngineType().name());
                    item.put("audit_time_utc", row.getAuditTimeUtc());
                    item.put("blocked_reason", blankAs(row.getBlockedReason(), ""));
                    item.put("decision_after_risk", row.getDecisionAfterRisk() == null ? "" : row.getDecisionAfterRisk().name());
                    item.put("confidence_after", scale(row.getConfidenceAfter(), 4));
                    return item;
                })
                .toList();
        for (Map<String, Object> row : signalAudits) {
            refs.add(Map.of(
                    "ref_type", "signal_audit",
                    "id", String.valueOf(row.get("id")),
                    "engine_type", String.valueOf(row.get("engine_type")),
                    "audit_time_utc", String.valueOf(row.get("audit_time_utc"))));
        }

        Map<String, Object> signalSummary = new LinkedHashMap<>();
        signalSummary.put("signal_id", signal == null ? "" : blankAs(signal.getId(), ""));
        signalSummary.put("asset_code", assetCode == null ? "" : assetCode);
        signalSummary.put("asset_name", asset == null ? "" : blankAs(asset.getAssetName(), ""));
        signalSummary.put("country", signal == null ? "" : blankAs(signal.getCountry(), ""));
        signalSummary.put("theme", signal == null ? "" : blankAs(signal.getTheme(), ""));
        signalSummary.put("action", signal == null || signal.getAction() == null ? "WATCH" : signal.getAction().name());
        signalSummary.put("good_news_probability", scale(signal == null ? null : signal.getGoodNewsProbability(), 4));
        signalSummary.put("bad_news_probability", scale(signal == null ? null : signal.getBadNewsProbability(), 4));
        signalSummary.put("combined_confidence", scale(signal == null ? null : signal.getCombinedConfidence(), 4));
        signalSummary.put("blocked", signal != null && signal.getBlockedReason() != null && !signal.getBlockedReason().isBlank());
        signalSummary.put("blocked_reason", signal == null ? "" : blankAs(signal.getBlockedReason(), ""));
        signalSummary.put("data_state", scalarText(scalpBreakdown == null ? null : scalpBreakdown.get("data_state")));
        signalSummary.put("news_count_mapped", intValue(scalpBreakdown == null ? null : scalpBreakdown.get("mapped_news_count")));
        signalSummary.put("news_count_eligible", intValue(scalpBreakdown == null ? null : scalpBreakdown.get("eligible_news_count")));
        signalSummary.put("generated_at", signal == null ? null : signal.getGeneratedAt());

        refs.add(Map.of(
                "ref_type", "signal",
                "signal_id", String.valueOf(signalSummary.get("signal_id")),
                "asset_code", String.valueOf(signalSummary.get("asset_code")),
                "generated_at", String.valueOf(signalSummary.get("generated_at"))));

        String refsJson = toJson(refs);
        return new SignalDetailRagContext(
                blankAs(signal == null ? null : signal.getId(), ""),
                blankAs(assetCode, ""),
                asset == null ? "" : blankAs(asset.getAssetName(), ""),
                signal == null ? "" : blankAs(signal.getCountry(), ""),
                signal == null ? "" : blankAs(signal.getTheme(), ""),
                signal == null ? "" : blankAs(signal.getTraceId(), ""),
                signalSummary,
                safeList(riskChecks),
                recentNews,
                quoteSummary,
                barSummary,
                signalAudits,
                refs,
                refsJson);
    }

    public TraceDetailRagContext buildTraceDetailContext(String traceId, Map<String, Object> traceData) {
        Map<String, Object> counts = castMap(traceData == null ? null : traceData.get("counts"));
        Map<String, Object> marketCollection = castMap(traceData == null ? null : traceData.get("market_collection"));
        Map<String, Object> signals = castMap(traceData == null ? null : traceData.get("signals"));
        List<?> toggles = traceData == null ? List.of() : safeRawList(traceData.get("feature_toggles"));

        List<?> providerAudits = safeRawList(marketCollection.get("provider_audits"));
        List<?> gapEvents = safeRawList(marketCollection.get("gap_events"));
        List<?> signalAudits = safeRawList(signals.get("signal_audits"));
        List<?> strategyRuns = safeRawList(signals.get("strategy_runs"));

        List<Map<String, Object>> refs = new ArrayList<>();
        refs.add(Map.of("ref_type", "trace", "trace_id", blankAs(traceId, "")));
        for (Object row : providerAudits.stream().limit(5).toList()) {
            if (row instanceof Map<?, ?> map) {
                refs.add(Map.of(
                        "ref_type", "provider_audit",
                        "id", String.valueOf(map.get("id")),
                        "provider", String.valueOf(map.get("provider")),
                        "success", String.valueOf(map.get("success"))));
            }
        }
        for (Object row : signalAudits.stream().limit(5).toList()) {
            if (row instanceof Map<?, ?> map) {
                refs.add(Map.of(
                        "ref_type", "signal_audit",
                        "id", String.valueOf(map.get("id")),
                        "asset_code", String.valueOf(map.get("asset_code")),
                        "engine_type", String.valueOf(map.get("engine_type"))));
            }
        }

        Map<String, Object> signalSummary = new LinkedHashMap<>();
        signalSummary.put("strategy_runs", strategyRuns.size());
        signalSummary.put("signal_audits", signalAudits.size());
        signalSummary.put("blocked_signal_audits", signalAudits.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(map -> {
                    Object blocked = map.get("blocked_reason");
                    return blocked != null && !String.valueOf(blocked).isBlank();
                })
                .count());

        Map<String, Object> marketSummary = new LinkedHashMap<>();
        marketSummary.put("provider_audits", providerAudits.size());
        marketSummary.put("gap_events", gapEvents.size());
        marketSummary.put("failed_provider_audits", providerAudits.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(map -> !Boolean.parseBoolean(String.valueOf(map.get("success"))))
                .count());
        marketSummary.put("quality_snapshots", safeRawList(marketCollection.get("quality_snapshots")).size());

        return new TraceDetailRagContext(
                blankAs(traceId, ""),
                counts,
                marketSummary,
                signalSummary,
                Map.of("feature_toggle_count", toggles.size()),
                refs,
                toJson(refs));
    }

    private Map<String, Object> buildQuoteSummary(List<MarketQuoteSnapshotEntity> rows) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            summary.put("missing_quote", true);
            summary.put("stale", true);
            return summary;
        }
        MarketQuoteSnapshotEntity latest = rows.get(0);
        summary.put("missing_quote", false);
        summary.put("snapshot_utc", latest.getSnapshotUtc());
        summary.put("provider", blankAs(latest.getProviderName(), ""));
        summary.put("last_price", scale(latest.getLastPrice(), 6));
        summary.put("change_pct", scale(latest.getChangePct(), 6));
        summary.put("volume", scale(latest.getVolume(), 4));
        long ageMinutes = latest.getSnapshotUtc() == null
                ? Long.MAX_VALUE
                : ChronoUnit.MINUTES.between(latest.getSnapshotUtc(), OffsetDateTime.now());
        summary.put("age_minutes", ageMinutes);
        summary.put("stale", ageMinutes > 180);
        if (rows.size() > 1) {
            MarketQuoteSnapshotEntity previous = rows.get(1);
            BigDecimal latestPrice = firstNonNull(latest.getLastPrice(), BigDecimal.ZERO);
            BigDecimal prevPrice = firstNonNull(previous.getLastPrice(), BigDecimal.ZERO);
            if (prevPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal delta = latestPrice.subtract(prevPrice)
                        .divide(prevPrice, 6, RoundingMode.HALF_UP);
                summary.put("delta_vs_prev_snapshot_pct", scale(delta, 6));
            }
        }
        return summary;
    }

    private Map<String, Object> buildBarSummary(List<MarketPriceBarEntity> bars, String timeframe) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("timeframe", blankAs(timeframe, "1m"));
        summary.put("bar_count", bars == null ? 0 : bars.size());
        if (bars == null || bars.isEmpty()) {
            summary.put("insufficient_bars", true);
            return summary;
        }
        List<MarketPriceBarEntity> ordered = new ArrayList<>(bars);
        ordered.sort(Comparator.comparing(
                row -> firstNonNull(row.getBarTimeUtc(), row.getBarTime(), OffsetDateTime.MIN)));
        MarketPriceBarEntity first = ordered.get(0);
        MarketPriceBarEntity last = ordered.get(ordered.size() - 1);
        BigDecimal firstClose = firstNonNull(first.getClosePrice(), BigDecimal.ZERO);
        BigDecimal lastClose = firstNonNull(last.getClosePrice(), BigDecimal.ZERO);
        BigDecimal totalVolume = ordered.stream()
                .map(row -> firstNonNull(row.getVolume(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        summary.put("first_bar_time_utc", firstNonNull(first.getBarTimeUtc(), first.getBarTime(), null));
        summary.put("last_bar_time_utc", firstNonNull(last.getBarTimeUtc(), last.getBarTime(), null));
        summary.put("first_close", scale(firstClose, 6));
        summary.put("last_close", scale(lastClose, 6));
        if (firstClose.compareTo(BigDecimal.ZERO) > 0) {
            summary.put("window_return_pct", scale(
                    lastClose.subtract(firstClose).divide(firstClose, 6, RoundingMode.HALF_UP),
                    6));
        } else {
            summary.put("window_return_pct", BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP));
        }
        summary.put("total_volume", scale(totalVolume, 4));
        summary.put("insufficient_bars", ordered.size() < 5);
        return summary;
    }

    private List<String> safeList(List<String> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().filter(v -> v != null && !v.isBlank()).toList();
    }

    private List<?> safeRawList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private String scalarText(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? "" : text;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String abbreviate(String value, int max) {
        String text = scalarText(value);
        if (text.length() <= Math.max(0, max)) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String blankAs(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * 종목 상세 보조 분석 컨텍스트.
     */
    public record SignalDetailRagContext(
            String signalId,
            String assetCode,
            String assetName,
            String country,
            String theme,
            String traceId,
            Map<String, Object> signalSummary,
            List<String> riskChecks,
            List<Map<String, Object>> recentNews,
            Map<String, Object> quoteSummary,
            Map<String, Object> barSummary,
            List<Map<String, Object>> signalAudits,
            List<Map<String, Object>> contextRefs,
            String contextRefsJson) {
    }

    /**
     * 관리자 trace 상세 보조 요약 컨텍스트.
     */
    public record TraceDetailRagContext(
            String traceId,
            Map<String, Object> counts,
            Map<String, Object> marketSummary,
            Map<String, Object> signalSummary,
            Map<String, Object> featureToggleSummary,
            List<Map<String, Object>> contextRefs,
            String contextRefsJson) {
    }
}

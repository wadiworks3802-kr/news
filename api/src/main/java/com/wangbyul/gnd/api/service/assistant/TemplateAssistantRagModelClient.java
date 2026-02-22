package com.wangbyul.gnd.api.service.assistant;

import com.wangbyul.gnd.api.config.AssistantRagProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 외부 LLM 연동 전 단계에서 사용하는 경량 템플릿 기반 보조 모델 클라이언트.
 *
 * 규칙 엔진 결과를 변경하지 않고, 사람이 읽기 쉬운 요약/주의 문구만 생성한다.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Component
public class TemplateAssistantRagModelClient implements AssistantRagModelClient {

    private final AssistantRagProperties properties;

    public TemplateAssistantRagModelClient(AssistantRagProperties properties) {
        this.properties = properties;
    }

    @Override
    public SignalDetailModelOutput generateSignalDetailSummary(AssistantRagContextBuilderService.SignalDetailRagContext context) {
        Map<String, Object> signal = context.signalSummary();
        Map<String, Object> quote = context.quoteSummary();
        Map<String, Object> bars = context.barSummary();
        List<Map<String, Object>> newsItems = context.recentNews();

        String action = safeText(signal.get("action"), "WATCH");
        String dataState = safeText(signal.get("data_state"), "");
        BigDecimal good = decimal(signal.get("good_news_probability"));
        BigDecimal bad = decimal(signal.get("bad_news_probability"));
        BigDecimal combined = decimal(signal.get("combined_confidence"));
        BigDecimal quoteChangePct = decimal(quote.get("change_pct"));
        BigDecimal intradayReturnPct = decimal(bars.get("window_return_pct"));
        String topEvent = topEventType(newsItems);

        String summary = String.format(
                Locale.ROOT,
                "%s의 규칙엔진 기본 판단은 %s이며, 최근 연결 뉴스 %d건과 시세/바 요약을 기준으로 설명 보조를 제공합니다. "
                        + "호재 %.3f / 악재 %.3f / 결합 %.3f 수준이며, 대표 이벤트는 %s 입니다.",
                blankAs(context.assetName(), context.assetCode()),
                action,
                newsItems.size(),
                good.doubleValue(),
                bad.doubleValue(),
                combined.doubleValue(),
                topEvent);

        List<String> evidence = new ArrayList<>();
        evidence.add("연결 뉴스 " + newsItems.size() + "건 기반 이벤트 요약(" + topEvent + ")");
        if (quote.containsKey("change_pct")) {
            evidence.add("최신 시세 변화율 " + fmtPct(quoteChangePct) + " (provider=" + safeText(quote.get("provider"), "N/A") + ")");
        }
        if (bars.containsKey("timeframe")) {
            evidence.add("최근 " + safeText(bars.get("timeframe"), "bar") + " 바 수 "
                    + intValue(bars.get("bar_count")) + "건 / 구간수익률 " + fmtPct(intradayReturnPct));
        }
        evidence.add("규칙엔진 액션 유지 잠금: " + action);

        List<String> cautions = new ArrayList<>();
        if (!dataState.isBlank() && properties.getDataGapStates().stream().anyMatch(s -> s.equalsIgnoreCase(dataState))) {
            cautions.add("데이터 부족 상태(" + dataState + ")로 수치 해석보다 관찰/보류 중심으로 봐야 합니다.");
        }
        if (Boolean.TRUE.equals(signal.get("blocked"))) {
            cautions.add("리스크 정책 차단이 존재하므로 보조 설명은 주문 허용 의미가 아닙니다.");
        }
        if (Boolean.TRUE.equals(quote.get("stale"))) {
            cautions.add("시세 스냅샷이 지연되어 최신 가격 반응 해석 신뢰도가 낮습니다.");
        }
        if (Boolean.TRUE.equals(bars.get("insufficient_bars"))) {
            cautions.add("바 데이터 표본이 적어 차트 추세 해석이 제한됩니다.");
        }

        List<String> missing = new ArrayList<>();
        if (newsItems.isEmpty()) {
            missing.add("연결 뉴스 근거가 부족합니다.");
        }
        if (Boolean.TRUE.equals(quote.get("missing_quote"))) {
            missing.add("최신 시세 스냅샷이 없습니다.");
        }
        if (Boolean.TRUE.equals(bars.get("insufficient_bars"))) {
            missing.add("충분한 바 데이터가 없습니다.");
        }

        List<String> changeTriggers = List.of(
                "고신뢰 신규 뉴스가 추가 수집되면 호/악재 확률 설명이 바뀔 수 있습니다.",
                "가격/거래량 반응(특히 최근 " + safeText(bars.get("timeframe"), "1m") + " 바) 변화 시 설명 요약이 갱신됩니다.",
                "리스크 차단 해제 또는 BUY_LOCK 해제 시 판단 해석 문구가 달라질 수 있습니다.");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("generator", properties.getModelMode());
        meta.put("top_event_type", topEvent);
        meta.put("news_count", newsItems.size());

        return new SignalDetailModelOutput(
                summary,
                evidence,
                cautions,
                missing,
                changeTriggers,
                "규칙엔진 결과(" + action + ")를 유지한 상태에서 뉴스/시세/리스크 근거를 요약한 참고 설명입니다.",
                action,
                true,
                meta);
    }

    @Override
    public TraceDetailModelOutput generateTraceDetailSummary(AssistantRagContextBuilderService.TraceDetailRagContext context) {
        Map<String, Object> counts = context.counts();
        Map<String, Object> signalSummary = context.signalSummary();
        Map<String, Object> marketSummary = context.marketSummary();

        int signalAudits = intValue(counts.get("signal_audits"));
        int providerAudits = intValue(counts.get("provider_audits"));
        int gapEvents = intValue(counts.get("gap_events"));
        int strategyRuns = intValue(counts.get("strategy_runs"));
        int blockedSignals = intValue(signalSummary.get("blocked_signal_audits"));
        int failedProviderCalls = intValue(marketSummary.get("failed_provider_audits"));

        String summary = String.format(
                Locale.ROOT,
                "trace %s 에 대해 시장수집 감사 %d건, 시그널 감사 %d건, 전략 실행 %d건이 확인되었습니다. "
                        + "차단 시그널 %d건, 시장 갭 이벤트 %d건, provider 실패 감사 %d건 기준으로 운영 상태를 요약합니다.",
                context.traceId(),
                providerAudits,
                signalAudits,
                strategyRuns,
                blockedSignals,
                gapEvents,
                failedProviderCalls);

        List<String> highlights = new ArrayList<>();
        highlights.add("전략 실행(strategy_runs): " + strategyRuns + "건");
        highlights.add("시그널 감사(signal_audits): " + signalAudits + "건 / 차단 " + blockedSignals + "건");
        highlights.add("시장 수집 감사(provider_audits): " + providerAudits + "건 / 실패 " + failedProviderCalls + "건");

        List<String> cautions = new ArrayList<>();
        if (gapEvents > 0) {
            cautions.add("시장 데이터 갭 이벤트가 존재하므로 일부 신호 설명이 보수적으로 생성되었을 수 있습니다.");
        }
        if (blockedSignals > 0) {
            cautions.add("리스크/시간정렬 차단된 시그널이 포함되어 있으므로 추천 신호와 차단 신호를 구분해서 확인해야 합니다.");
        }
        if (failedProviderCalls > 0) {
            cautions.add("시장데이터 provider 호출 실패가 발생해 fallback 수집 경로가 사용되었을 수 있습니다.");
        }
        if (cautions.isEmpty()) {
            cautions.add("즉시 확인이 필요한 강한 경고는 발견되지 않았습니다. 상세 행 단위 점검으로 이어가면 됩니다.");
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("generator", properties.getModelMode());
        meta.put("counts_snapshot", counts);
        meta.put("context_ref_count", context.contextRefs().size());

        return new TraceDetailModelOutput(
                summary,
                highlights,
                cautions,
                "trace 단위 수집/시그널/토글/감사 정보를 사람이 읽기 쉬운 문장으로 정리한 참고 요약입니다.",
                meta);
    }

    private String topEventType(List<Map<String, Object>> newsItems) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : newsItems) {
            String event = safeText(row.get("event_type"), "UNKNOWN");
            counts.merge(event, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("UNKNOWN");
    }

    private String fmtPct(BigDecimal value) {
        return value.multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(4, RoundingMode.HALF_UP);
        }
        try {
            return new BigDecimal(String.valueOf(value)).setScale(4, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
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

    private String safeText(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private String blankAs(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}

package com.wangbyul.gnd.api.service.signal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.api.config.BacktestPolicyProperties;
import com.wangbyul.gnd.api.config.BacktestValidationMode;
import com.wangbyul.gnd.api.dto.BacktestComparisonDto;
import com.wangbyul.gnd.api.dto.BacktestReportDto;
import com.wangbyul.gnd.core.domain.JobStatus;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.StrategyRunEntity;
import com.wangbyul.gnd.core.domain.StrategyRunType;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.repository.StrategyRunRepository;
import com.wangbyul.gnd.core.repository.TradingSignalRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 백테스트 과최적화 방지 + 검증 리포트 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class BacktestComparisonService {

    private static final BigDecimal NEWS_RETURN_SCALE = BigDecimal.valueOf(10d);
    private static final BigDecimal CHART_RETURN_SCALE = BigDecimal.valueOf(12d);
    private static final BigDecimal FUSION_RETURN_SCALE = BigDecimal.valueOf(15d);
    private static final BigDecimal OVERFIT_GAP_WARNING_THRESHOLD = BigDecimal.valueOf(0.08d);

    private final TradingSignalRepository tradingSignalRepository;
    private final TradingSignalEngineService tradingSignalEngineService;
    private final StrategyRunRepository strategyRunRepository;
    private final BacktestPolicyProperties backtestPolicyProperties;
    private final ObjectMapper objectMapper;

    public BacktestComparisonService(
            TradingSignalRepository tradingSignalRepository,
            TradingSignalEngineService tradingSignalEngineService,
            StrategyRunRepository strategyRunRepository,
            BacktestPolicyProperties backtestPolicyProperties,
            ObjectMapper objectMapper) {
        this.tradingSignalRepository = tradingSignalRepository;
        this.tradingSignalEngineService = tradingSignalEngineService;
        this.strategyRunRepository = strategyRunRepository;
        this.backtestPolicyProperties = backtestPolicyProperties;
        this.objectMapper = objectMapper;
    }

    public BacktestComparisonDto compare(String country, String theme, String period) {
        return compare(country, theme, period, null);
    }

    public BacktestComparisonDto compare(String country, String theme, String period, String validationModeOverride) {
        OffsetDateTime generatedAt = OffsetDateTime.now();
        List<TradingSignalEntity> rows = loadRows(country, theme, period);
        ValidationContext validationContext = buildValidationContext(rows, validationModeOverride);

        List<TradingSignalEntity> evaluationRows = validationContext.evaluationRows();
        int lookAheadViolationCount = countLookAheadViolations(evaluationRows);
        List<String> warnings = new ArrayList<>(validationContext.warnings());
        if (lookAheadViolationCount > 0) {
            warnings.add("look_ahead_violation_detected:" + lookAheadViolationCount);
        }

        int minTradeThreshold = Math.max(1, nullSafe(backtestPolicyProperties.getMinimumTradeCountThreshold(), 20));
        int oosTradeCount = countActionable(validationContext.outSampleRows());
        int inSampleTradeCount = countActionable(validationContext.inSampleRows());
        int evalTradeCount = countActionable(evaluationRows);
        boolean minimumTradeCountWarning = evalTradeCount < minTradeThreshold;
        if (minimumTradeCountWarning) {
            warnings.add("minimum_trade_count_below_threshold:" + evalTradeCount + "/" + minTradeThreshold);
        }

        BigDecimal newsOnlyReturn = average(evaluationRows.stream().map(this::newsOnlyReturn).toList());
        BigDecimal chartOnlyReturn = average(evaluationRows.stream().map(this::chartOnlyReturn).toList());
        BigDecimal fusionBeforeReturn = average(evaluationRows.stream().map(signal -> fusionReturn(signal, false)).toList());
        BigDecimal fusionAfterReturn = average(evaluationRows.stream().map(signal -> fusionReturn(signal, true)).toList());

        BigDecimal inSampleAfter = average(validationContext.inSampleRows().stream()
                .map(signal -> fusionReturn(signal, true))
                .toList());
        BigDecimal outSampleAfter = average(validationContext.outSampleRows().stream()
                .map(signal -> fusionReturn(signal, true))
                .toList());
        if (Boolean.TRUE.equals(backtestPolicyProperties.getOverfitWarningEnabled())
                && validationContext.inSampleRows().size() > 0
                && validationContext.outSampleRows().size() > 0) {
            BigDecimal gap = inSampleAfter.subtract(outSampleAfter).abs();
            if (gap.compareTo(OVERFIT_GAP_WARNING_THRESHOLD) >= 0) {
                warnings.add("overfit_warning_in_sample_out_sample_gap:" + scale(gap));
            }
        }

        int overtradeBefore = countActionable(evaluationRows);
        int overtradeAfter = (int) evaluationRows.stream().filter(this::isPolicyActionable).count();
        BigDecimal beforeMdd = estimateMdd(evaluationRows, false);
        BigDecimal afterMdd = estimateMdd(evaluationRows, true);
        BigDecimal buyLockEffect = ratio(
                BigDecimal.valueOf(overtradeBefore - overtradeAfter),
                BigDecimal.valueOf(Math.max(1, overtradeBefore)));
        BigDecimal avgDownEffect = average(evaluationRows.stream()
                .map(signal -> Boolean.TRUE.equals(signal.getAvgDownAllowed()) ? BigDecimal.valueOf(0.02d) : BigDecimal.valueOf(-0.01d))
                .toList());

        Map<String, Object> reportJson = new LinkedHashMap<>();
        reportJson.put("generated_at", generatedAt);
        reportJson.put("country", country);
        reportJson.put("theme", theme == null ? "" : theme);
        reportJson.put("period", period);
        reportJson.put("validation", Map.of(
                "mode", validationContext.mode().name(),
                "out_of_sample_required", Boolean.TRUE.equals(backtestPolicyProperties.getOutOfSampleRequired()),
                "in_sample_size", validationContext.inSampleRows().size(),
                "out_of_sample_size", validationContext.outSampleRows().size(),
                "evaluation_size", evaluationRows.size(),
                "walk_forward_fold_count", validationContext.walkForwardFoldSummaries().size(),
                "look_ahead_violation_count", lookAheadViolationCount));
        Map<String, Object> compare = new LinkedHashMap<>();
        compare.put("policy_before_return", scale(fusionBeforeReturn));
        compare.put("policy_after_return", scale(fusionAfterReturn));
        compare.put("news_only_return", scale(newsOnlyReturn));
        compare.put("chart_only_return", scale(chartOnlyReturn));
        compare.put("fusion_return", scale(fusionAfterReturn));
        compare.put("before_policy_mdd", scale(beforeMdd));
        compare.put("after_policy_mdd", scale(afterMdd));
        compare.put("buy_lock_effect", scale(buyLockEffect));
        compare.put("avg_down_policy_effect", scale(avgDownEffect));
        compare.put("overtrade_before", overtradeBefore);
        compare.put("overtrade_after", overtradeAfter);
        compare.put("in_sample_trade_count", inSampleTradeCount);
        compare.put("out_of_sample_trade_count", oosTradeCount);
        reportJson.put("compare", compare);
        reportJson.put("walk_forward", validationContext.walkForwardFoldSummaries());
        reportJson.put("warnings", warnings);

        StrategyRunEntity run = saveBacktestRun(
                country,
                theme,
                rows.size(),
                evaluationRows.size(),
                generatedAt,
                reportJson);

        String summary = "mode=" + validationContext.mode().name()
                + ", policy_after=" + percent(fusionAfterReturn) + "%"
                + ", news/chart/fusion=" + percent(newsOnlyReturn) + "%/"
                + percent(chartOnlyReturn) + "%/"
                + percent(fusionAfterReturn) + "%"
                + ", OOS trades=" + oosTradeCount;

        return BacktestComparisonDto.builder()
                .reportRunId(run.getId())
                .generatedAt(generatedAt)
                .country(country)
                .theme(theme == null ? "" : theme)
                .period(period)
                .baselineNewsOnlyReturn(scale(newsOnlyReturn))
                .baselineChartOnlyReturn(scale(chartOnlyReturn))
                .fusionReturn(scale(fusionAfterReturn))
                .beforePolicyMdd(scale(beforeMdd))
                .afterPolicyMdd(scale(afterMdd))
                .overtradeBefore(overtradeBefore)
                .overtradeAfter(overtradeAfter)
                .buyLockEffect(scale(buyLockEffect))
                .avgDownPolicyEffect(scale(avgDownEffect))
                .summary(summary)
                .validationMode(validationContext.mode().name())
                .outOfSampleRequired(Boolean.TRUE.equals(backtestPolicyProperties.getOutOfSampleRequired()))
                .lookAheadViolationCount(lookAheadViolationCount)
                .minimumTradeCountWarning(minimumTradeCountWarning)
                .inSampleTradeCount(inSampleTradeCount)
                .outOfSampleTradeCount(oosTradeCount)
                .policyBeforeReturn(scale(fusionBeforeReturn))
                .policyAfterReturn(scale(fusionAfterReturn))
                .reportJson(reportJson)
                .warnings(warnings)
                .build();
    }

    public List<BacktestReportDto> getReports(String country, String theme, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<StrategyRunEntity> rows = strategyRunRepository.findByRunTypeAndScopeCountryOrderByStartedAtDesc(
                        StrategyRunType.BACKTEST,
                        country,
                        PageRequest.of(0, safeLimit))
                .stream()
                .filter(run -> theme == null || theme.isBlank() || Objects.equals(theme, run.getScopeTheme()))
                .toList();

        return rows.stream()
                .map(run -> BacktestReportDto.builder()
                        .runId(run.getId())
                        .country(run.getScopeCountry())
                        .theme(run.getScopeTheme() == null ? "" : run.getScopeTheme())
                        .runType(run.getRunType().name())
                        .startedAt(run.getStartedAt())
                        .endedAt(run.getEndedAt())
                        .processedCount(run.getProcessedCount())
                        .createdSignalCount(run.getCreatedSignalCount())
                        .resultSummaryJson(parseJsonMap(run.getResultSummaryJson()))
                        .build())
                .toList();
    }

    private List<TradingSignalEntity> loadRows(String country, String theme, String period) {
        OffsetDateTime since = resolveSince(period);
        int maxRows = Math.max(100, nullSafe(backtestPolicyProperties.getMaxRows(), 1000));

        List<TradingSignalEntity> rows = tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                country,
                since,
                PageRequest.of(0, maxRows));

        rows = rows.stream()
                .filter(signal -> theme == null || theme.isBlank() || Objects.equals(theme, signal.getTheme()))
                .filter(signal -> signal.getGeneratedAt() != null && !signal.getGeneratedAt().isAfter(OffsetDateTime.now()))
                .sorted(Comparator.comparing(TradingSignalEntity::getGeneratedAt))
                .toList();

        if (rows.isEmpty()) {
            tradingSignalEngineService.generateSignals(country, theme, 40, StrategyRunType.BACKTEST);
            rows = tradingSignalRepository.findByCountryAndGeneratedAtAfterOrderByGeneratedAtDesc(
                    country,
                    since,
                    PageRequest.of(0, maxRows));
            rows = rows.stream()
                    .filter(signal -> theme == null || theme.isBlank() || Objects.equals(theme, signal.getTheme()))
                    .filter(signal -> signal.getGeneratedAt() != null && !signal.getGeneratedAt().isAfter(OffsetDateTime.now()))
                    .sorted(Comparator.comparing(TradingSignalEntity::getGeneratedAt))
                    .toList();
        }
        return rows;
    }

    private ValidationContext buildValidationContext(List<TradingSignalEntity> rows, String overrideMode) {
        BacktestValidationMode mode = resolveMode(overrideMode);
        boolean outRequired = Boolean.TRUE.equals(backtestPolicyProperties.getOutOfSampleRequired());
        List<String> warnings = new ArrayList<>();

        if (rows.isEmpty()) {
            if (outRequired) {
                warnings.add("out_of_sample_required_but_no_data");
            }
            return new ValidationContext(mode, List.of(), List.of(), List.of(), List.of(), warnings);
        }

        if (mode == BacktestValidationMode.WALK_FORWARD) {
            return buildWalkForwardContext(rows, outRequired, warnings);
        }

        if (mode == BacktestValidationMode.TRAIN_TEST_SPLIT) {
            BigDecimal ratio = normalizeRatio(backtestPolicyProperties.getTrainSplitRatio(), BigDecimal.valueOf(0.70d));
            int splitIndex = BigDecimal.valueOf(rows.size())
                    .multiply(ratio)
                    .setScale(0, RoundingMode.HALF_UP)
                    .intValue();
            splitIndex = Math.max(1, Math.min(rows.size() - 1, splitIndex));

            List<TradingSignalEntity> inSample = rows.subList(0, splitIndex);
            List<TradingSignalEntity> outSample = rows.subList(splitIndex, rows.size());
            if (outRequired && outSample.isEmpty()) {
                warnings.add("train_test_split_out_of_sample_empty");
            }

            List<TradingSignalEntity> evaluationRows = outRequired
                    ? (outSample.isEmpty() ? rows : outSample)
                    : (outSample.isEmpty() ? rows : outSample);
            return new ValidationContext(mode, inSample, outSample, evaluationRows, List.of(), warnings);
        }

        // NONE: 구조 검증은 유지하되 전체 데이터 평가
        if (outRequired) {
            warnings.add("validation_mode_none_with_out_of_sample_required_uses_full_data");
        }
        return new ValidationContext(mode, rows, rows, rows, List.of(), warnings);
    }

    private ValidationContext buildWalkForwardContext(
            List<TradingSignalEntity> rows,
            boolean outRequired,
            List<String> warnings) {
        int trainSize = Math.max(1, nullSafe(backtestPolicyProperties.getWalkForwardTrainSize(), 120));
        int testSize = Math.max(1, nullSafe(backtestPolicyProperties.getWalkForwardTestSize(), 30));
        int step = Math.max(1, nullSafe(backtestPolicyProperties.getWalkForwardStepSize(), 30));

        List<TradingSignalEntity> outSampleAll = new ArrayList<>();
        List<Map<String, Object>> foldSummaries = new ArrayList<>();
        int fold = 0;

        for (int start = 0; start + trainSize + testSize <= rows.size(); start += step) {
            int trainStart = start;
            int trainEnd = trainStart + trainSize;
            int testStart = trainEnd;
            int testEnd = testStart + testSize;

            List<TradingSignalEntity> trainRows = rows.subList(trainStart, trainEnd);
            List<TradingSignalEntity> testRows = rows.subList(testStart, testEnd);
            outSampleAll.addAll(testRows);

            fold++;
            foldSummaries.add(Map.of(
                    "fold", fold,
                    "train_size", trainRows.size(),
                    "test_size", testRows.size(),
                    "train_start", trainRows.get(0).getGeneratedAt(),
                    "train_end", trainRows.get(trainRows.size() - 1).getGeneratedAt(),
                    "test_start", testRows.get(0).getGeneratedAt(),
                    "test_end", testRows.get(testRows.size() - 1).getGeneratedAt(),
                    "test_trade_count", countActionable(testRows)));
        }

        if (foldSummaries.isEmpty()) {
            warnings.add("walk_forward_no_valid_fold");
        }
        if (outRequired && outSampleAll.isEmpty()) {
            warnings.add("walk_forward_out_of_sample_empty");
        }

        List<TradingSignalEntity> evaluationRows = outRequired
                ? outSampleAll
                : (outSampleAll.isEmpty() ? rows : outSampleAll);

        return new ValidationContext(
                BacktestValidationMode.WALK_FORWARD,
                rows,
                outSampleAll,
                evaluationRows,
                foldSummaries,
                warnings);
    }

    private StrategyRunEntity saveBacktestRun(
            String country,
            String theme,
            int processedCount,
            int createdSignalCount,
            OffsetDateTime now,
            Map<String, Object> reportJson) {
        StrategyRunEntity run = new StrategyRunEntity();
        run.setRunType(StrategyRunType.BACKTEST);
        run.setScopeCountry(country);
        run.setScopeTheme(theme == null ? "" : theme);
        run.setStatus(JobStatus.SUCCESS);
        run.setStartedAt(now);
        run.setEndedAt(now);
        run.setProcessedCount(processedCount);
        run.setCreatedSignalCount(createdSignalCount);
        String json = toJson(reportJson);
        run.setReportJson(json);
        run.setResultSummaryJson(json);
        run.setTraceId(traceId());
        return strategyRunRepository.save(run);
    }

    private BacktestValidationMode resolveMode(String overrideMode) {
        if (overrideMode == null || overrideMode.isBlank()) {
            return backtestPolicyProperties.getValidationMode();
        }
        try {
            return BacktestValidationMode.valueOf(overrideMode.trim().toUpperCase());
        } catch (Exception ignored) {
            return backtestPolicyProperties.getValidationMode();
        }
    }

    private int countLookAheadViolations(List<TradingSignalEntity> rows) {
        OffsetDateTime now = OffsetDateTime.now();
        int count = 0;
        for (TradingSignalEntity row : rows) {
            if (row.getGeneratedAt() != null && row.getGeneratedAt().isAfter(now)) {
                count++;
                continue;
            }
            boolean futureBlocked = (row.getBlockedReason() != null && row.getBlockedReason().contains("FUTURE_DATA_BLOCKED"))
                    || (row.getRiskChecks() != null && row.getRiskChecks().contains("future_market_data_blocked"));
            if (futureBlocked && isActionable(row.getAction())) {
                count++;
            }
        }
        return count;
    }

    private int countActionable(List<TradingSignalEntity> rows) {
        return (int) rows.stream().filter(row -> isActionable(row.getAction())).count();
    }

    private boolean isPolicyActionable(TradingSignalEntity row) {
        if (row == null) {
            return false;
        }
        if (!isActionable(row.getAction())) {
            return false;
        }
        if (Boolean.TRUE.equals(row.getReanalysisLockRequired())) {
            return false;
        }
        return !SignalActionType.BUY_LOCK.equals(row.getAction());
    }

    private boolean isActionable(SignalActionType action) {
        return action == SignalActionType.BUY_CANDIDATE || action == SignalActionType.SELL_CANDIDATE;
    }

    private BigDecimal newsOnlyReturn(TradingSignalEntity signal) {
        return safe(signal.getGoodNewsProbability())
                .subtract(safe(signal.getBadNewsProbability()))
                .multiply(NEWS_RETURN_SCALE);
    }

    private BigDecimal chartOnlyReturn(TradingSignalEntity signal) {
        return safe(signal.getChartConfidence())
                .subtract(BigDecimal.valueOf(0.5d))
                .multiply(CHART_RETURN_SCALE);
    }

    private BigDecimal fusionReturn(TradingSignalEntity signal, boolean withPolicy) {
        BigDecimal base = safe(signal.getCombinedConfidence())
                .subtract(BigDecimal.valueOf(0.5d))
                .multiply(FUSION_RETURN_SCALE);
        if (!withPolicy) {
            return base;
        }
        BigDecimal adjusted = base;
        if (Boolean.TRUE.equals(signal.getReanalysisLockRequired())) {
            adjusted = adjusted.multiply(BigDecimal.valueOf(0.4d));
        }
        if (!Boolean.TRUE.equals(signal.getAvgDownAllowed())) {
            adjusted = adjusted.multiply(BigDecimal.valueOf(0.8d));
        }
        return adjusted;
    }

    private OffsetDateTime resolveSince(String period) {
        return switch (period) {
            case "7d" -> OffsetDateTime.now().minusDays(7);
            case "90d" -> OffsetDateTime.now().minusDays(90);
            default -> OffsetDateTime.now().minusDays(30);
        };
    }

    private BigDecimal estimateMdd(List<TradingSignalEntity> rows, boolean withPolicy) {
        BigDecimal equity = BigDecimal.ONE;
        BigDecimal peak = BigDecimal.ONE;
        BigDecimal minDrawdown = BigDecimal.ZERO;
        for (TradingSignalEntity signal : rows) {
            BigDecimal base = safe(signal.getCombinedConfidence())
                    .subtract(BigDecimal.valueOf(0.5d))
                    .multiply(BigDecimal.valueOf(0.04d));
            if (withPolicy && Boolean.TRUE.equals(signal.getReanalysisLockRequired())) {
                base = base.multiply(BigDecimal.valueOf(0.4d));
            }
            if (withPolicy && !Boolean.TRUE.equals(signal.getAvgDownAllowed())) {
                base = base.multiply(BigDecimal.valueOf(0.8d));
            }
            equity = equity.multiply(BigDecimal.ONE.add(base));
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            BigDecimal drawdown = ratio(equity.subtract(peak), peak);
            if (drawdown.compareTo(minDrawdown) < 0) {
                minDrawdown = drawdown;
            }
        }
        return minDrawdown;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal num, BigDecimal den) {
        if (den == null || den.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return num.divide(den, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value) {
        return safe(value).setScale(4, RoundingMode.HALF_UP);
    }

    private String percent(BigDecimal value) {
        return scale(value.multiply(BigDecimal.valueOf(100d))).toPlainString();
    }

    private int nullSafe(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private BigDecimal normalizeRatio(BigDecimal value, BigDecimal fallback) {
        BigDecimal ratio = value == null ? fallback : value;
        if (ratio.compareTo(BigDecimal.valueOf(0.1d)) < 0) {
            return BigDecimal.valueOf(0.1d);
        }
        if (ratio.compareTo(BigDecimal.valueOf(0.9d)) > 0) {
            return BigDecimal.valueOf(0.9d);
        }
        return ratio;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private Map<String, Object> parseJsonMap(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of("raw", rawJson);
        }
    }

    private String traceId() {
        String trace = MDC.get("trace_id");
        return trace == null ? "" : trace;
    }

    private record ValidationContext(
            BacktestValidationMode mode,
            List<TradingSignalEntity> inSampleRows,
            List<TradingSignalEntity> outSampleRows,
            List<TradingSignalEntity> evaluationRows,
            List<Map<String, Object>> walkForwardFoldSummaries,
            List<String> warnings) {
    }
}

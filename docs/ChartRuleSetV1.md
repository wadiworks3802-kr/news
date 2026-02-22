# ChartRuleSetV1

작성자 : 안태욱  
현재날짜 : 2026년 02월 20일

## 1) 지표
- 이동평균: `MA5`, `MA20`, `MA60`, `MA120`
- ATR: `app.signal.chart-atr-period`
- 거래량 비율: 최근/이전 평균(`app.signal.chart-volume-average-bars`)
- 고점/저점 갱신: `app.signal.chart-high-low-lookback-bars`
- 추세 기울기(%): `app.signal.chart-trend-slope-lookback-bars`

## 2) LONG_BIAS 룰 (6개)
1. `close > MA20`
2. `MA20 > MA60`
3. `MA60 > MA120`
4. `volume_ratio >= app.signal.chart-volume-ratio-min`
5. `trend_slope_pct >= app.signal.chart-trend-slope-min-pct`
6. `new_high_breakout == true`

- 히트 수가 `app.signal.chart-long-bias-min-rule-hits` 이상이면 LONG_BIAS

## 3) SHORT_BIAS 룰 (6개)
1. `close < MA20`
2. `MA20 < MA60`
3. `MA60 < MA120`
4. `volume_ratio >= app.signal.chart-volume-ratio-min`
5. `trend_slope_pct <= -app.signal.chart-trend-slope-min-pct`
6. `new_low_breakdown == true`

- 히트 수가 `app.signal.chart-short-bias-min-rule-hits` 이상이면 SHORT_BIAS

## 4) trend_breakdown_severe
- 기본 조건:
  - `close < MA120`
  - `distance_to_ma120 >= app.signal.chart-trend-breakdown-pct`
- 보조 조건:
  - `new_low_breakdown == true`
  - `distance_to_ma120 >= ATR * app.signal.chart-trend-breakdown-atr-multiplier`

## 5) avg_down_allowed
다음을 모두 만족해야 `true`:
1. `trend_breakdown_severe == false`
2. 최근 악재 확률 과도 아님(`bad_news_probability < app.signal.bad-news-threshold`)
3. 유동성 점수 하한 충족
4. 분할매수 단계 한도 미초과
5. 차트 지지 조건 충족 (`LONG_BIAS` 또는 `chart_confidence >= app.signal.chart-avg-down-min-confidence`)

## 6) pressure 룰셋
- `pressure_window_bars = app.signal.pressure-window-bars`
- `pressure_threshold = app.signal.pressure-detection-threshold`
- `sell_pressure_ratio = sell_bars / pressure_window_bars`
- `buy_pressure_ratio = buy_bars / pressure_window_bars`
- `volume_regime_same = (volume_diff_pct <= app.signal.volume-same-tolerance-pct)`
- `sell_pressure_is_negative = sell_pressure_detected && !volume_regime_same`
- `buy_pressure_is_positive = buy_pressure_detected && !volume_regime_same`

## 7) 저장/감사
- `trading_signal.pressure_reason_json`
- `trading_signal.sell_pressure_detected`
- `trading_signal.sell_pressure_is_negative`
- `trading_signal.buy_pressure_detected`
- `trading_signal.buy_pressure_is_positive`
- `trading_signal.volume_regime_same`
- `signal_audit_log.rule_hits_json`에 차트/압력 근거 JSON 기록

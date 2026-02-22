# SignalRules (RULE_V1)

작성자 : 안태욱  
현재날짜 : 2026년 02월 20일

## 1) 확률 계산 모드
- `app.signal.probability-calculation-mode=RULE_V1` 일 때만 RULE_V1 계산을 수행한다.

## 2) 입력 뉴스 필터
- 자산 매칭(코드/이름/테마 문자열 포함) 실패 뉴스 제외
- `trust_score < app.signal.news-trust-score-min` 뉴스 제외
- `event_time > signal_time` 뉴스 제외 (look-ahead 방지)
- 분석 윈도우: `min(max(news-windows-minutes<=60), app.signal.news-price-alignment-window-minutes)`

## 3) 감성 판정
- 긍정 단어/부정 단어 사전 히트 수를 계산한다.
- `positive_ratio = pos_hits / (pos_hits + neg_hits)`
- `negative_ratio = neg_hits / (pos_hits + neg_hits)`
- 긍정 반영 조건: `positive_ratio >= app.signal.positive-sentiment-threshold`
- 부정 반영 조건: `negative_ratio >= app.signal.negative-sentiment-threshold`

## 4) 시간 감쇠
- `decay_weight = 0.5 ^ (age_minutes / app.signal.news-decay-half-life-minutes)`
- 뉴스 기여도는 `trust_score * decay_weight * sentiment_ratio`로 계산한다.

## 5) 확률 산출
- `positive_mass = Σ(긍정 기여도)`
- `negative_mass = Σ(부정 기여도)`
- 표본 부족(`eligible_news_count < app.signal.min-news-count-for-probability`) 또는 `positive_mass + negative_mass == 0` 이면:
  - `good_news_probability = 0.5`
  - `bad_news_probability = 0.5`
- 그 외:
  - `good_news_probability = positive_mass / (positive_mass + negative_mass)`
  - `bad_news_probability = negative_mass / (positive_mass + negative_mass)`

## 6) 신뢰도(news_confidence)
- 표본 부족:
  - `sample_ratio = eligible_news_count / min_news_count_for_probability`
  - `news_confidence = max(app.signal.insufficient-sample-confidence-floor, sample_ratio * avg_trust)`
- 표본 충족:
  - `news_confidence = avg_trust`
- 모든 확률/신뢰도 값은 0~1로 clamp 한다.

## 7) 저장 필드
- `trading_signal.good_news_probability`
- `trading_signal.bad_news_probability`
- `trading_signal.news_confidence`
- `trading_signal.probability_reason_breakdown_json`

## 8) 감사 로그 기록
- `signal_audit_log.rule_hits_json`에 RULE_V1 입력/임계치/질량값을 기록
- `signal_audit_log.risk_checks_json`에 정렬 검증/리스크 적용 전후 결과를 기록

/*
 * 인덱스 목적 요약
 * - idx_mdq_snapshot_time_desc: 품질 스냅샷 최신 조회
 * - idx_mdq_provider_country_theme: 공급자/국가/테마 단위 품질 비교
 * - idx_mdq_quality_score: 저품질 구간(임계 미달) 탐지
 * - idx_gap_event_time_desc: 누락/지연/이상 이벤트 최신 조회
 * - idx_gap_event_asset_type: 자산/이벤트유형 단위 원인 추적
 * - idx_gap_event_severity_resolved: 미해결 고위험 이벤트 우선 처리
 * - idx_api_audit_provider_time_desc: 외부 API 응답 감사 최신 조회
 * - idx_api_audit_api_name: API 이름 단위 지연/오류 분석
 * - idx_api_audit_success: 성공/실패 비율 진단
 * - idx_signal_audit_asset_time_desc: 자산별 시그널 의사결정 이력 재현
 * - idx_signal_audit_engine_type: 엔진별 정책 히트율/차단 사유 분석
 * - idx_signal_audit_signal_id: 시그널 상세 화면에서 감사로그 역추적
 */

ALTER TABLE news
    ADD COLUMN IF NOT EXISTS published_at_utc TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS fetched_at_utc TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS translated_at_utc TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS indexed_at_utc TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS event_time_source VARCHAR(32);

UPDATE news
SET published_at_utc = COALESCE(published_at_utc, pub_utc, fetch_utc, created_at),
    fetched_at_utc = COALESCE(fetched_at_utc, fetch_utc, created_at),
    event_time_source = COALESCE(
            event_time_source,
            CASE
                WHEN pub_utc IS NOT NULL THEN 'PUB_UTC'
                WHEN fetch_utc IS NOT NULL THEN 'FETCH_UTC'
                ELSE 'CREATED_AT'
                END
                        );

ALTER TABLE news
    ALTER COLUMN event_time_source SET DEFAULT 'PUB_UTC';

ALTER TABLE market_price_bar
    ADD COLUMN IF NOT EXISTS bar_time_utc TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS ingested_at TIMESTAMPTZ;

UPDATE market_price_bar
SET bar_time_utc = COALESCE(bar_time_utc, bar_time, created_at),
    ingested_at = COALESCE(ingested_at, created_at, NOW());

ALTER TABLE market_price_bar
    ALTER COLUMN ingested_at SET DEFAULT NOW();

ALTER TABLE market_quote_snapshot
    ADD COLUMN IF NOT EXISTS quote_time_utc TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS ingested_at TIMESTAMPTZ;

UPDATE market_quote_snapshot
SET quote_time_utc = COALESCE(quote_time_utc, snapshot_utc, created_at),
    ingested_at = COALESCE(ingested_at, created_at, NOW());

ALTER TABLE market_quote_snapshot
    ALTER COLUMN ingested_at SET DEFAULT NOW();

CREATE TABLE market_data_quality_snapshot (
    id BIGSERIAL PRIMARY KEY,
    snapshot_time_utc TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    provider VARCHAR(40) NOT NULL,
    country VARCHAR(10) NOT NULL,
    theme VARCHAR(64),
    asset_count_expected INTEGER NOT NULL DEFAULT 0,
    asset_count_collected INTEGER NOT NULL DEFAULT 0,
    quote_count_expected INTEGER NOT NULL DEFAULT 0,
    quote_count_collected INTEGER NOT NULL DEFAULT 0,
    bar_count_expected INTEGER NOT NULL DEFAULT 0,
    bar_count_collected INTEGER NOT NULL DEFAULT 0,
    missing_rate NUMERIC(8, 6) NOT NULL DEFAULT 0,
    delay_rate NUMERIC(8, 6) NOT NULL DEFAULT 0,
    duplicate_rate NUMERIC(8, 6) NOT NULL DEFAULT 0,
    anomaly_rate NUMERIC(8, 6) NOT NULL DEFAULT 0,
    quality_score NUMERIC(6, 2) NOT NULL DEFAULT 0,
    summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    trace_id VARCHAR(64)
);

CREATE TABLE market_data_gap_event (
    id BIGSERIAL PRIMARY KEY,
    event_time_utc TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    asset_code VARCHAR(32) REFERENCES asset_universe(asset_code),
    provider VARCHAR(40) NOT NULL,
    timeframe VARCHAR(16),
    event_type VARCHAR(40) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    expected_time_utc TIMESTAMPTZ,
    actual_time_utc TIMESTAMPTZ,
    delay_seconds BIGINT,
    detail_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_at TIMESTAMPTZ,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE api_response_audit (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(40) NOT NULL,
    api_name VARCHAR(80) NOT NULL,
    request_time_utc TIMESTAMPTZ NOT NULL,
    response_time_utc TIMESTAMPTZ,
    latency_ms BIGINT,
    http_status INTEGER,
    request_hash VARCHAR(128),
    response_hash VARCHAR(128),
    record_count INTEGER,
    sample_payload_json JSONB,
    success BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(80),
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE signal_audit_log (
    id BIGSERIAL PRIMARY KEY,
    signal_id VARCHAR(64) REFERENCES trading_signal(id),
    asset_code VARCHAR(32) NOT NULL REFERENCES asset_universe(asset_code),
    audit_time_utc TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    engine_type VARCHAR(24) NOT NULL,
    input_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    rule_hits_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    risk_checks_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    decision_before_risk VARCHAR(24),
    decision_after_risk VARCHAR(24),
    blocked_reason TEXT,
    confidence_before NUMERIC(8, 6),
    confidence_after NUMERIC(8, 6),
    model_version VARCHAR(64),
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mdq_snapshot_time_desc ON market_data_quality_snapshot (snapshot_time_utc DESC);
CREATE INDEX idx_mdq_provider_country_theme ON market_data_quality_snapshot (provider, country, theme);
CREATE INDEX idx_mdq_quality_score ON market_data_quality_snapshot (quality_score);

CREATE INDEX idx_gap_event_time_desc ON market_data_gap_event (event_time_utc DESC);
CREATE INDEX idx_gap_event_asset_type ON market_data_gap_event (asset_code, event_type);
CREATE INDEX idx_gap_event_severity_resolved ON market_data_gap_event (severity, resolved);

CREATE INDEX idx_api_audit_provider_time_desc ON api_response_audit (provider, request_time_utc DESC);
CREATE INDEX idx_api_audit_api_name ON api_response_audit (api_name);
CREATE INDEX idx_api_audit_success ON api_response_audit (success);

CREATE INDEX idx_signal_audit_asset_time_desc ON signal_audit_log (asset_code, audit_time_utc DESC);
CREATE INDEX idx_signal_audit_engine_type ON signal_audit_log (engine_type);
CREATE INDEX idx_signal_audit_signal_id ON signal_audit_log (signal_id);

COMMENT ON TABLE news IS '정규화된 뉴스 본문과 파생 데이터 저장 테이블';
COMMENT ON COLUMN news.published_at_utc IS '원문 기사 발행 시각(시간 정렬 기준)';
COMMENT ON COLUMN news.fetched_at_utc IS '외부 소스에서 기사 수집 완료 시각';
COMMENT ON COLUMN news.translated_at_utc IS '한국어 번역 완료 시각';
COMMENT ON COLUMN news.indexed_at_utc IS '검색/시그널 인덱싱 완료 시각';
COMMENT ON COLUMN news.event_time_source IS '대표 이벤트 시각의 기준 컬럼(PUB_UTC/FETCH_UTC/CREATED_AT)';

COMMENT ON TABLE market_price_bar IS '자산별 OHLCV 바 시계열 저장 테이블';
COMMENT ON COLUMN market_price_bar.bar_time_utc IS '시장 이벤트 기준 bar 시각(UTC)';
COMMENT ON COLUMN market_price_bar.ingested_at IS '시스템 저장 시각(UTC)';

COMMENT ON TABLE market_quote_snapshot IS '자산별 실시간 호가/체결 스냅샷 저장 테이블';
COMMENT ON COLUMN market_quote_snapshot.quote_time_utc IS '시장 이벤트 기준 시세 시각(UTC)';
COMMENT ON COLUMN market_quote_snapshot.ingested_at IS '시스템 저장 시각(UTC)';

COMMENT ON TABLE market_data_quality_snapshot IS '시장데이터 수집 품질 스냅샷 집계 테이블';
COMMENT ON COLUMN market_data_quality_snapshot.id IS '품질 스냅샷 PK';
COMMENT ON COLUMN market_data_quality_snapshot.snapshot_time_utc IS '품질 집계 시각(UTC)';
COMMENT ON COLUMN market_data_quality_snapshot.provider IS '시장데이터 공급자 코드';
COMMENT ON COLUMN market_data_quality_snapshot.country IS '국가 코드(KR, US 등)';
COMMENT ON COLUMN market_data_quality_snapshot.theme IS '테마 코드(선택)';
COMMENT ON COLUMN market_data_quality_snapshot.asset_count_expected IS '해당 구간 기대 자산 수';
COMMENT ON COLUMN market_data_quality_snapshot.asset_count_collected IS '수집 성공 자산 수';
COMMENT ON COLUMN market_data_quality_snapshot.quote_count_expected IS '기대 시세 건수';
COMMENT ON COLUMN market_data_quality_snapshot.quote_count_collected IS '수집 시세 건수';
COMMENT ON COLUMN market_data_quality_snapshot.bar_count_expected IS '기대 바 데이터 건수';
COMMENT ON COLUMN market_data_quality_snapshot.bar_count_collected IS '수집 바 데이터 건수';
COMMENT ON COLUMN market_data_quality_snapshot.missing_rate IS '누락률(0~1)';
COMMENT ON COLUMN market_data_quality_snapshot.delay_rate IS '지연률(0~1)';
COMMENT ON COLUMN market_data_quality_snapshot.duplicate_rate IS '중복률(0~1)';
COMMENT ON COLUMN market_data_quality_snapshot.anomaly_rate IS '이상치율(0~1)';
COMMENT ON COLUMN market_data_quality_snapshot.quality_score IS '종합 품질 점수(0~100)';
COMMENT ON COLUMN market_data_quality_snapshot.summary_json IS '품질 원인/샘플/권고사항 JSON';
COMMENT ON COLUMN market_data_quality_snapshot.created_at IS '레코드 생성 시각';
COMMENT ON COLUMN market_data_quality_snapshot.trace_id IS '요청/배치 추적 ID';

COMMENT ON TABLE market_data_gap_event IS '시장데이터 누락/지연/이상 이벤트 상세 로그';
COMMENT ON COLUMN market_data_gap_event.id IS '갭 이벤트 PK';
COMMENT ON COLUMN market_data_gap_event.event_time_utc IS '이벤트 감지 시각(UTC)';
COMMENT ON COLUMN market_data_gap_event.asset_code IS '관련 자산 코드(없으면 전체 범위 이벤트)';
COMMENT ON COLUMN market_data_gap_event.provider IS '공급자 코드';
COMMENT ON COLUMN market_data_gap_event.timeframe IS '바 타임프레임(quote 이벤트는 null 가능)';
COMMENT ON COLUMN market_data_gap_event.event_type IS '이벤트 유형(MISSING_BAR, DELAYED_QUOTE 등)';
COMMENT ON COLUMN market_data_gap_event.severity IS '심각도(INFO/WARN/ERROR)';
COMMENT ON COLUMN market_data_gap_event.expected_time_utc IS '기대 수집 시각';
COMMENT ON COLUMN market_data_gap_event.actual_time_utc IS '실제 수집 시각';
COMMENT ON COLUMN market_data_gap_event.delay_seconds IS '지연 시간(초)';
COMMENT ON COLUMN market_data_gap_event.detail_json IS '이벤트 상세 원인/샘플 JSON';
COMMENT ON COLUMN market_data_gap_event.resolved IS '해결 여부';
COMMENT ON COLUMN market_data_gap_event.resolved_at IS '해결 처리 시각';
COMMENT ON COLUMN market_data_gap_event.trace_id IS '요청/배치 추적 ID';
COMMENT ON COLUMN market_data_gap_event.created_at IS '레코드 생성 시각';

COMMENT ON TABLE api_response_audit IS '외부 Provider API 응답 감사 샘플 로그';
COMMENT ON COLUMN api_response_audit.id IS 'API 응답 감사 PK';
COMMENT ON COLUMN api_response_audit.provider IS '외부 데이터 공급자';
COMMENT ON COLUMN api_response_audit.api_name IS '호출 API 이름';
COMMENT ON COLUMN api_response_audit.request_time_utc IS '요청 시작 시각';
COMMENT ON COLUMN api_response_audit.response_time_utc IS '응답 수신 시각';
COMMENT ON COLUMN api_response_audit.latency_ms IS '왕복 지연(ms)';
COMMENT ON COLUMN api_response_audit.http_status IS 'HTTP 상태코드';
COMMENT ON COLUMN api_response_audit.request_hash IS '요청 파라미터 해시';
COMMENT ON COLUMN api_response_audit.response_hash IS '응답 본문 해시';
COMMENT ON COLUMN api_response_audit.record_count IS '응답 레코드 수';
COMMENT ON COLUMN api_response_audit.sample_payload_json IS '민감정보 제거 후 샘플 응답 JSON';
COMMENT ON COLUMN api_response_audit.success IS '호출 성공 여부';
COMMENT ON COLUMN api_response_audit.error_code IS '실패 코드';
COMMENT ON COLUMN api_response_audit.trace_id IS '요청/배치 추적 ID';
COMMENT ON COLUMN api_response_audit.created_at IS '레코드 생성 시각';

COMMENT ON TABLE signal_audit_log IS '시그널 생성 의사결정 과정 감사 로그';
COMMENT ON COLUMN signal_audit_log.id IS '시그널 감사 PK';
COMMENT ON COLUMN signal_audit_log.signal_id IS '연결된 시그널 ID';
COMMENT ON COLUMN signal_audit_log.asset_code IS '분석 대상 자산 코드';
COMMENT ON COLUMN signal_audit_log.audit_time_utc IS '감사 로그 생성 시각(UTC)';
COMMENT ON COLUMN signal_audit_log.engine_type IS '엔진 타입(SCALP/SWING/POSITION/DISCOVERY/FUSION)';
COMMENT ON COLUMN signal_audit_log.input_snapshot_json IS '입력값 스냅샷(뉴스/가격/거래량/지표)';
COMMENT ON COLUMN signal_audit_log.rule_hits_json IS '룰셋 히트 목록/결과';
COMMENT ON COLUMN signal_audit_log.risk_checks_json IS '리스크 점검 결과';
COMMENT ON COLUMN signal_audit_log.decision_before_risk IS '리스크 적용 전 의사결정';
COMMENT ON COLUMN signal_audit_log.decision_after_risk IS '리스크 적용 후 의사결정';
COMMENT ON COLUMN signal_audit_log.blocked_reason IS '차단 사유';
COMMENT ON COLUMN signal_audit_log.confidence_before IS '리스크 적용 전 신뢰도';
COMMENT ON COLUMN signal_audit_log.confidence_after IS '리스크 적용 후 신뢰도';
COMMENT ON COLUMN signal_audit_log.model_version IS '룰/모델 버전';
COMMENT ON COLUMN signal_audit_log.trace_id IS '요청/배치 추적 ID';
COMMENT ON COLUMN signal_audit_log.created_at IS '레코드 생성 시각';

COMMENT ON INDEX idx_mdq_snapshot_time_desc IS '품질 스냅샷 최신 조회 인덱스';
COMMENT ON INDEX idx_mdq_provider_country_theme IS '공급자/국가/테마 품질 비교 인덱스';
COMMENT ON INDEX idx_mdq_quality_score IS '저품질 스냅샷 탐지 인덱스';
COMMENT ON INDEX idx_gap_event_time_desc IS '갭 이벤트 최신 조회 인덱스';
COMMENT ON INDEX idx_gap_event_asset_type IS '자산/이벤트유형 추적 인덱스';
COMMENT ON INDEX idx_gap_event_severity_resolved IS '미해결 고심각 이벤트 추적 인덱스';
COMMENT ON INDEX idx_api_audit_provider_time_desc IS 'API 감사 최신 조회 인덱스';
COMMENT ON INDEX idx_api_audit_api_name IS 'API 이름별 진단 인덱스';
COMMENT ON INDEX idx_api_audit_success IS '성공/실패 비율 진단 인덱스';
COMMENT ON INDEX idx_signal_audit_asset_time_desc IS '자산별 시그널 감사 이력 인덱스';
COMMENT ON INDEX idx_signal_audit_engine_type IS '엔진 타입별 감사 분석 인덱스';
COMMENT ON INDEX idx_signal_audit_signal_id IS '시그널 상세 추적 인덱스';

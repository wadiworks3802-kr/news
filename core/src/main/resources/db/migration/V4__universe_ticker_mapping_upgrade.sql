/*
 * 인덱스 목적 요약
 * - idx_asset_universe_selection: 국가/테마별 동적 유니버스 우선순위 조회
 * - idx_asset_universe_core_watch: 핵심/관심 종목 분포 진단
 * - idx_asset_universe_verify: 검증 상태/최근 검증시각 조회
 * - idx_ticker_alias_lookup: 별칭 기반 티커 역검색
 * - idx_ticker_alias_asset: 자산별 별칭 목록 조회
 * - idx_ticker_alias_active: 활성 별칭 필터링
 * - idx_ticker_alias_audit_time: 별칭 검증 이력 최신 조회
 * - idx_ticker_alias_audit_asset: 자산별 검증 이력 조회
 * - idx_ticker_alias_audit_valid: 유효/무효 비율 진단
 * - idx_mapping_quality_report_scope_time: 국가/테마별 매핑 품질 시계열 조회
 * - idx_mapping_quality_report_score: 저품질 리포트 탐지
 */

ALTER TABLE asset_universe
    ADD COLUMN IF NOT EXISTS selection_source VARCHAR(30),
    ADD COLUMN IF NOT EXISTS selection_score NUMERIC(5, 2),
    ADD COLUMN IF NOT EXISTS market_cap_rank INTEGER,
    ADD COLUMN IF NOT EXISTS avg_volume_rank INTEGER,
    ADD COLUMN IF NOT EXISTS is_core_asset BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_watchlist_asset BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS display_weight INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS verification_status VARCHAR(20);

UPDATE asset_universe
SET selection_source = COALESCE(selection_source, 'MANUAL'),
    selection_score = COALESCE(selection_score, 0),
    verification_status = COALESCE(verification_status, 'UNVERIFIED');

CREATE INDEX IF NOT EXISTS idx_asset_universe_selection
    ON asset_universe (country, theme, selection_score DESC, display_weight DESC);
CREATE INDEX IF NOT EXISTS idx_asset_universe_core_watch
    ON asset_universe (country, is_core_asset, is_watchlist_asset);
CREATE INDEX IF NOT EXISTS idx_asset_universe_verify
    ON asset_universe (verification_status, last_verified_at DESC);

CREATE TABLE IF NOT EXISTS ticker_alias_dictionary (
    id BIGSERIAL PRIMARY KEY,
    asset_code VARCHAR(32) NOT NULL REFERENCES asset_universe(asset_code),
    alias_value VARCHAR(200) NOT NULL,
    alias_type VARCHAR(30) NOT NULL,
    exchange_code VARCHAR(20),
    country VARCHAR(10),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ticker_alias_lookup
    ON ticker_alias_dictionary (alias_value, alias_type, country);
CREATE INDEX IF NOT EXISTS idx_ticker_alias_asset
    ON ticker_alias_dictionary (asset_code, alias_type);
CREATE INDEX IF NOT EXISTS idx_ticker_alias_active
    ON ticker_alias_dictionary (active);

CREATE TABLE IF NOT EXISTS ticker_alias_audit (
    id BIGSERIAL PRIMARY KEY,
    audit_time_utc TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    asset_code VARCHAR(32) REFERENCES asset_universe(asset_code),
    alias_value VARCHAR(200),
    alias_type VARCHAR(30),
    check_type VARCHAR(40) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    valid BOOLEAN NOT NULL DEFAULT FALSE,
    detail_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ticker_alias_audit_time
    ON ticker_alias_audit (audit_time_utc DESC);
CREATE INDEX IF NOT EXISTS idx_ticker_alias_audit_asset
    ON ticker_alias_audit (asset_code, audit_time_utc DESC);
CREATE INDEX IF NOT EXISTS idx_ticker_alias_audit_valid
    ON ticker_alias_audit (valid, severity);

CREATE TABLE IF NOT EXISTS mapping_quality_report (
    id BIGSERIAL PRIMARY KEY,
    report_time_utc TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    country VARCHAR(10),
    theme VARCHAR(64),
    sample_size INTEGER NOT NULL DEFAULT 0,
    direct_match_precision NUMERIC(8, 6) NOT NULL DEFAULT 0,
    theme_match_false_positive_rate NUMERIC(8, 6) NOT NULL DEFAULT 0,
    country_theme_overexpansion_rate NUMERIC(8, 6) NOT NULL DEFAULT 0,
    link_score_avg NUMERIC(8, 6) NOT NULL DEFAULT 0,
    link_score_p50 NUMERIC(8, 6) NOT NULL DEFAULT 0,
    link_score_p90 NUMERIC(8, 6) NOT NULL DEFAULT 0,
    summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mapping_quality_report_scope_time
    ON mapping_quality_report (country, theme, report_time_utc DESC);
CREATE INDEX IF NOT EXISTS idx_mapping_quality_report_score
    ON mapping_quality_report (direct_match_precision, theme_match_false_positive_rate);

COMMENT ON TABLE asset_universe IS '뉴스/시장데이터/시그널 분석에 사용하는 자산 유니버스 마스터';
COMMENT ON COLUMN asset_universe.selection_source IS '유니버스 선정 방식(MANUAL, MARKET_CAP, VOLUME, THEME_LEADER, WATCHLIST, DISCOVERY)';
COMMENT ON COLUMN asset_universe.selection_score IS '유니버스 선정 점수(0~100)';
COMMENT ON COLUMN asset_universe.market_cap_rank IS '국가/시장 기준 시가총액 순위(없으면 null)';
COMMENT ON COLUMN asset_universe.avg_volume_rank IS '최근 거래량 기준 순위';
COMMENT ON COLUMN asset_universe.is_core_asset IS '국가/테마 대표 핵심 자산 여부';
COMMENT ON COLUMN asset_universe.is_watchlist_asset IS '사용자 관심자산 여부';
COMMENT ON COLUMN asset_universe.display_weight IS 'UI 노출 우선순위 가중치';
COMMENT ON COLUMN asset_universe.last_verified_at IS '티커/종목명 매핑 검증 시각';
COMMENT ON COLUMN asset_universe.verification_status IS '검증 상태(VERIFIED, UNVERIFIED, FAILED)';

COMMENT ON TABLE ticker_alias_dictionary IS '티커/한글명/영문명/약칭 별칭 사전';
COMMENT ON COLUMN ticker_alias_dictionary.id IS '티커 별칭 PK';
COMMENT ON COLUMN ticker_alias_dictionary.asset_code IS '연결 자산 코드';
COMMENT ON COLUMN ticker_alias_dictionary.alias_value IS '별칭 값(티커/한글명/영문명/약칭)';
COMMENT ON COLUMN ticker_alias_dictionary.alias_type IS '별칭 유형(TICKER, KO_NAME, EN_NAME, SHORT_NAME)';
COMMENT ON COLUMN ticker_alias_dictionary.exchange_code IS '거래소 코드(KRX, NASDAQ, NYSE 등)';
COMMENT ON COLUMN ticker_alias_dictionary.country IS '국가 코드';
COMMENT ON COLUMN ticker_alias_dictionary.active IS '별칭 활성 여부';
COMMENT ON COLUMN ticker_alias_dictionary.created_at IS '생성 시각';
COMMENT ON COLUMN ticker_alias_dictionary.updated_at IS '수정 시각';

COMMENT ON TABLE ticker_alias_audit IS '티커 별칭 검증 배치 결과 로그';
COMMENT ON COLUMN ticker_alias_audit.id IS '티커 별칭 검증 로그 PK';
COMMENT ON COLUMN ticker_alias_audit.audit_time_utc IS '검증 수행 시각';
COMMENT ON COLUMN ticker_alias_audit.asset_code IS '검증 대상 자산 코드';
COMMENT ON COLUMN ticker_alias_audit.alias_value IS '검증 대상 별칭';
COMMENT ON COLUMN ticker_alias_audit.alias_type IS '검증 대상 별칭 유형';
COMMENT ON COLUMN ticker_alias_audit.check_type IS '검증 항목(FORMAT, EXCHANGE, NAME_MAPPING, DUPLICATE_ALIAS, LOCALE_CONFLICT)';
COMMENT ON COLUMN ticker_alias_audit.severity IS '심각도(INFO/WARN/ERROR)';
COMMENT ON COLUMN ticker_alias_audit.valid IS '검증 통과 여부';
COMMENT ON COLUMN ticker_alias_audit.detail_json IS '검증 상세/원인 JSON';
COMMENT ON COLUMN ticker_alias_audit.trace_id IS '추적 ID';
COMMENT ON COLUMN ticker_alias_audit.created_at IS '생성 시각';

COMMENT ON TABLE mapping_quality_report IS '뉴스-자산 매핑 품질 진단 리포트';
COMMENT ON COLUMN mapping_quality_report.id IS '매핑 품질 리포트 PK';
COMMENT ON COLUMN mapping_quality_report.report_time_utc IS '리포트 생성 시각';
COMMENT ON COLUMN mapping_quality_report.country IS '국가 코드';
COMMENT ON COLUMN mapping_quality_report.theme IS '테마 코드';
COMMENT ON COLUMN mapping_quality_report.sample_size IS '진단 대상 링크 샘플 수';
COMMENT ON COLUMN mapping_quality_report.direct_match_precision IS '직접 언급 매칭 정밀도';
COMMENT ON COLUMN mapping_quality_report.theme_match_false_positive_rate IS '테마 매칭 오탐률';
COMMENT ON COLUMN mapping_quality_report.country_theme_overexpansion_rate IS '국가/테마 과확장률';
COMMENT ON COLUMN mapping_quality_report.link_score_avg IS '링크 점수 평균';
COMMENT ON COLUMN mapping_quality_report.link_score_p50 IS '링크 점수 P50';
COMMENT ON COLUMN mapping_quality_report.link_score_p90 IS '링크 점수 P90';
COMMENT ON COLUMN mapping_quality_report.summary_json IS '리포트 상세/샘플 JSON';
COMMENT ON COLUMN mapping_quality_report.trace_id IS '추적 ID';
COMMENT ON COLUMN mapping_quality_report.created_at IS '생성 시각';

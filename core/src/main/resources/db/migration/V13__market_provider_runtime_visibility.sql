/*
 * 2차 시장데이터 운영 가시성 강화:
 * - quote/bar 레코드 trace_id 저장 경로 추가
 * - provider 실행감사에 provider_error_code/message 명시 컬럼 추가
 * - 운영 진단/배포 검증 시 mock/실패 원인 추적 근거 보강
 */

ALTER TABLE market_quote_snapshot
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);

ALTER TABLE market_price_bar
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);

ALTER TABLE market_provider_job
    ADD COLUMN IF NOT EXISTS provider_error_code VARCHAR(128),
    ADD COLUMN IF NOT EXISTS provider_error_message TEXT;

COMMENT ON COLUMN market_quote_snapshot.quote_time_utc IS '시장 이벤트 기준 시세 시각(UTC)';
COMMENT ON COLUMN market_quote_snapshot.ingested_at IS '시스템 저장 시각(UTC)';
COMMENT ON COLUMN market_quote_snapshot.trace_id IS '수집 실행 trace_id (시장데이터 수집/진단 추적용)';

COMMENT ON COLUMN market_price_bar.bar_time_utc IS '시장 이벤트 기준 bar 시각(UTC)';
COMMENT ON COLUMN market_price_bar.ingested_at IS '시스템 저장 시각(UTC)';
COMMENT ON COLUMN market_price_bar.trace_id IS '수집 실행 trace_id (시장데이터 수집/진단 추적용)';

COMMENT ON COLUMN market_provider_job.provider_error_code IS 'Provider 원천 오류 코드(표준화 전/후 식별용)';
COMMENT ON COLUMN market_provider_job.provider_error_message IS 'Provider 원천 오류 메시지(민감정보 마스킹 후 저장)';

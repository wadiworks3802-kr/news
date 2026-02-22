/*
 * 1차 시장데이터 수집 기반 구축:
 * - Provider 추상화 수집 실행 이력(market_provider_job) 확장
 * - quote/bar/provider-job 테이블 COMMENT 보강
 * - 진단 API용 인덱스 추가
 */

ALTER TABLE market_provider_job
    ADD COLUMN IF NOT EXISTS job_name VARCHAR(80),
    ADD COLUMN IF NOT EXISTS triggered_by VARCHAR(64),
    ADD COLUMN IF NOT EXISTS requested_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS processed_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS success_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS failed_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS empty_response BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS latency_ms BIGINT,
    ADD COLUMN IF NOT EXISTS detail_json TEXT,
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);

UPDATE market_provider_job
SET job_name = COALESCE(job_name, CONCAT('market-', LOWER(COALESCE(job_type, 'link')))),
    requested_count = COALESCE(requested_count, 0),
    processed_count = COALESCE(processed_count, 0),
    success_count = COALESCE(success_count, 0),
    failed_count = COALESCE(failed_count, 0),
    empty_response = COALESCE(empty_response, FALSE),
    detail_json = COALESCE(detail_json, '{}');

CREATE INDEX IF NOT EXISTS idx_market_provider_job_type_time ON market_provider_job (job_type, scheduled_at DESC);
CREATE INDEX IF NOT EXISTS idx_market_provider_job_trace ON market_provider_job (trace_id, scheduled_at DESC);

COMMENT ON TABLE market_provider_job IS '시장 데이터 Provider 수집/헬스체크 실행 감사 이력';
COMMENT ON COLUMN market_provider_job.id IS '시장 Provider 잡 이력 PK';
COMMENT ON COLUMN market_provider_job.provider_name IS '실행 대상 Provider 코드(mock/toss/kiwoom 등)';
COMMENT ON COLUMN market_provider_job.job_name IS '실행 잡 이름(예: market-quote-collection)';
COMMENT ON COLUMN market_provider_job.asset_code IS '단일 자산 대상 실행 시 자산 코드(배치 전체 실행이면 null)';
COMMENT ON COLUMN market_provider_job.job_type IS '잡 유형(QUOTE/BAR/HEALTH_CHECK/LINK)';
COMMENT ON COLUMN market_provider_job.status IS '잡 실행 상태(QUEUED/RUNNING/SUCCESS/FAILED/DLQ)';
COMMENT ON COLUMN market_provider_job.attempt IS '재시도 포함 시도 횟수';
COMMENT ON COLUMN market_provider_job.triggered_by IS '실행 주체(batch-quartz/admin-api/manual 등)';
COMMENT ON COLUMN market_provider_job.requested_count IS 'Provider 요청 대상 개수(자산 수 등)';
COMMENT ON COLUMN market_provider_job.processed_count IS 'Provider 응답 처리 대상 개수';
COMMENT ON COLUMN market_provider_job.success_count IS '저장 성공 건수';
COMMENT ON COLUMN market_provider_job.failed_count IS '저장 실패/유효성 실패 건수';
COMMENT ON COLUMN market_provider_job.empty_response IS 'Provider 응답이 비어 있었는지 여부';
COMMENT ON COLUMN market_provider_job.latency_ms IS 'Provider 호출 왕복 지연 시간(ms)';
COMMENT ON COLUMN market_provider_job.last_error IS '마지막 실패 사유(민감정보 마스킹 후 저장)';
COMMENT ON COLUMN market_provider_job.detail_json IS '실행 상세 메타데이터(JSON 문자열)';
COMMENT ON COLUMN market_provider_job.trace_id IS '요청/배치 추적 ID';
COMMENT ON COLUMN market_provider_job.scheduled_at IS '잡 예약 시각';
COMMENT ON COLUMN market_provider_job.started_at IS '실제 실행 시작 시각';
COMMENT ON COLUMN market_provider_job.finished_at IS '실제 실행 종료 시각';
COMMENT ON COLUMN market_provider_job.updated_at IS '레코드 최종 갱신 시각';

COMMENT ON INDEX idx_market_provider_job_sched IS 'Provider/상태/예약시각 기준 실행 이력 조회 인덱스';
COMMENT ON INDEX idx_market_provider_job_type_time IS '잡 유형별 최신 실행 이력 조회 인덱스';
COMMENT ON INDEX idx_market_provider_job_trace IS 'trace_id 기반 실행 이력 상세 추적 인덱스';

COMMENT ON COLUMN market_quote_snapshot.id IS '시세 스냅샷 PK';
COMMENT ON COLUMN market_quote_snapshot.asset_code IS '자산 코드(asset_universe 참조)';
COMMENT ON COLUMN market_quote_snapshot.snapshot_utc IS '시세 스냅샷 시각(기존 호환 컬럼)';
COMMENT ON COLUMN market_quote_snapshot.last_price IS '마지막 체결가';
COMMENT ON COLUMN market_quote_snapshot.change_pct IS '전일 대비 등락률(%)';
COMMENT ON COLUMN market_quote_snapshot.bid_price IS '매수 최우선 호가';
COMMENT ON COLUMN market_quote_snapshot.ask_price IS '매도 최우선 호가';
COMMENT ON COLUMN market_quote_snapshot.bid_size IS '매수 잔량';
COMMENT ON COLUMN market_quote_snapshot.ask_size IS '매도 잔량';
COMMENT ON COLUMN market_quote_snapshot.spread_pct IS '호가 스프레드 비율(%)';
COMMENT ON COLUMN market_quote_snapshot.volume IS '누적 거래량 또는 공급자 제공 거래량';
COMMENT ON COLUMN market_quote_snapshot.provider_name IS '수집 Provider 코드';
COMMENT ON COLUMN market_quote_snapshot.created_at IS '레코드 생성 시각';

COMMENT ON COLUMN market_price_bar.id IS '바 데이터 PK';
COMMENT ON COLUMN market_price_bar.asset_code IS '자산 코드(asset_universe 참조)';
COMMENT ON COLUMN market_price_bar.bar_time IS '시장 이벤트 기준 bar 시각(기존 호환 컬럼)';
COMMENT ON COLUMN market_price_bar.timeframe IS '바 타임프레임(1m,5m,1h 등)';
COMMENT ON COLUMN market_price_bar.open_price IS '시가';
COMMENT ON COLUMN market_price_bar.high_price IS '고가';
COMMENT ON COLUMN market_price_bar.low_price IS '저가';
COMMENT ON COLUMN market_price_bar.close_price IS '종가';
COMMENT ON COLUMN market_price_bar.volume IS '거래량';
COMMENT ON COLUMN market_price_bar.provider_name IS '수집 Provider 코드';
COMMENT ON COLUMN market_price_bar.created_at IS '레코드 생성 시각';

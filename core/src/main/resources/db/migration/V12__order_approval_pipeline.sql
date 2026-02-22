/*
 * PROMPT-8-REBUILD 7차
 * - 관리자 승인 기반 주문 승인 파이프라인(준비 단계) 테이블 추가
 * - 추천/승인/주문요청/주문결과 감사 및 재현용 스냅샷 참조 저장
 */

CREATE TABLE IF NOT EXISTS order_approval_workflow (
    id BIGSERIAL PRIMARY KEY,
    signal_id VARCHAR(64) NOT NULL REFERENCES trading_signal(id),
    asset_code VARCHAR(32) NOT NULL REFERENCES asset_universe(asset_code),
    country VARCHAR(5),
    theme VARCHAR(64),
    order_side VARCHAR(8) NOT NULL,
    recommended_action VARCHAR(24),
    current_stage VARCHAR(32) NOT NULL DEFAULT 'RECOMMEND',
    recommendation_confidence NUMERIC(8, 4),
    recommendation_reason TEXT,
    analyze_at TIMESTAMPTZ,
    recommended_at TIMESTAMPTZ,
    approved_by VARCHAR(80),
    approved_at TIMESTAMPTZ,
    approval_reason TEXT,
    rejected_by VARCHAR(80),
    rejected_at TIMESTAMPTZ,
    reject_reason TEXT,
    order_requested_by VARCHAR(80),
    order_requested_at TIMESTAMPTZ,
    order_request_reason TEXT,
    order_executed_at TIMESTAMPTZ,
    order_execution_mode VARCHAR(32) NOT NULL DEFAULT 'PAPER_ONLY',
    paper_order_id BIGINT REFERENCES paper_trade_order(id),
    paper_order_status VARCHAR(24),
    paper_order_result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    order_error_code VARCHAR(80),
    order_error_message TEXT,
    live_trade_requested BOOLEAN NOT NULL DEFAULT FALSE,
    live_trade_blocked_reason VARCHAR(120),
    signal_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    signal_detail_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    quote_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    risk_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    news_context_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    assistant_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS order_approval_event_log (
    id BIGSERIAL PRIMARY KEY,
    workflow_id BIGINT NOT NULL REFERENCES order_approval_workflow(id) ON DELETE CASCADE,
    event_type VARCHAR(40) NOT NULL,
    from_stage VARCHAR(32),
    to_stage VARCHAR(32),
    actor VARCHAR(80),
    actor_role VARCHAR(40),
    reason TEXT,
    success BOOLEAN NOT NULL DEFAULT TRUE,
    error_code VARCHAR(80),
    request_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_order_approval_workflow_stage_updated
    ON order_approval_workflow (current_stage, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_approval_workflow_country_stage
    ON order_approval_workflow (country, current_stage, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_approval_workflow_signal
    ON order_approval_workflow (signal_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_approval_workflow_trace
    ON order_approval_workflow (trace_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_approval_workflow_paper_order
    ON order_approval_workflow (paper_order_id);

CREATE INDEX IF NOT EXISTS idx_order_approval_event_workflow
    ON order_approval_event_log (workflow_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_approval_event_trace
    ON order_approval_event_log (trace_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_approval_event_type
    ON order_approval_event_log (event_type, created_at DESC);

COMMENT ON TABLE order_approval_workflow IS 'AI 추천 기반 주문 승인 파이프라인 워크플로(준비 단계)';
COMMENT ON COLUMN order_approval_workflow.id IS '주문 승인 파이프라인 워크플로 PK';
COMMENT ON COLUMN order_approval_workflow.signal_id IS '원본 추천 시그널 ID';
COMMENT ON COLUMN order_approval_workflow.asset_code IS '대상 자산 코드';
COMMENT ON COLUMN order_approval_workflow.country IS '자산 국가 스코프';
COMMENT ON COLUMN order_approval_workflow.theme IS '자산/추천 테마';
COMMENT ON COLUMN order_approval_workflow.order_side IS '추천 주문 방향(BUY/SELL)';
COMMENT ON COLUMN order_approval_workflow.recommended_action IS '원본 시그널 액션(BUY_CANDIDATE 등)';
COMMENT ON COLUMN order_approval_workflow.current_stage IS '현재 파이프라인 단계(ANALYZE/RECOMMEND/APPROVE/ORDER_* 등)';
COMMENT ON COLUMN order_approval_workflow.recommendation_confidence IS '추천 시점 결합 신뢰도 스냅샷';
COMMENT ON COLUMN order_approval_workflow.recommendation_reason IS '추천 요약 사유(관리자 목록용)';
COMMENT ON COLUMN order_approval_workflow.analyze_at IS '입력 스냅샷 수집/분석 시각';
COMMENT ON COLUMN order_approval_workflow.recommended_at IS '추천 생성 시각';
COMMENT ON COLUMN order_approval_workflow.approved_by IS '승인자 식별자';
COMMENT ON COLUMN order_approval_workflow.approved_at IS '승인 시각';
COMMENT ON COLUMN order_approval_workflow.approval_reason IS '승인 사유';
COMMENT ON COLUMN order_approval_workflow.rejected_by IS '반려자 식별자';
COMMENT ON COLUMN order_approval_workflow.rejected_at IS '반려 시각';
COMMENT ON COLUMN order_approval_workflow.reject_reason IS '반려 사유';
COMMENT ON COLUMN order_approval_workflow.order_requested_by IS '주문 요청자 식별자';
COMMENT ON COLUMN order_approval_workflow.order_requested_at IS '주문 요청 시각';
COMMENT ON COLUMN order_approval_workflow.order_request_reason IS '주문 요청 사유';
COMMENT ON COLUMN order_approval_workflow.order_executed_at IS '주문 결과 반영 시각';
COMMENT ON COLUMN order_approval_workflow.order_execution_mode IS '주문 실행 모드(PAPER_ONLY/LIVE_DISABLED_PAPER 등)';
COMMENT ON COLUMN order_approval_workflow.paper_order_id IS '연결된 모의주문 ID';
COMMENT ON COLUMN order_approval_workflow.paper_order_status IS '모의주문 최종 상태(FILLED/BLOCKED 등)';
COMMENT ON COLUMN order_approval_workflow.paper_order_result_json IS '모의주문 실행 결과 스냅샷 JSON';
COMMENT ON COLUMN order_approval_workflow.order_error_code IS '주문 요청/실행 실패 코드';
COMMENT ON COLUMN order_approval_workflow.order_error_message IS '주문 요청/실행 실패 메시지';
COMMENT ON COLUMN order_approval_workflow.live_trade_requested IS '실주문 경로 요청 여부(준비 단계에서는 기본 false)';
COMMENT ON COLUMN order_approval_workflow.live_trade_blocked_reason IS '실주문 경로 차단 사유';
COMMENT ON COLUMN order_approval_workflow.signal_snapshot_json IS '추천 입력 시그널 요약 스냅샷 JSON';
COMMENT ON COLUMN order_approval_workflow.signal_detail_snapshot_json IS '추천 입력 시그널 상세 스냅샷 JSON';
COMMENT ON COLUMN order_approval_workflow.quote_snapshot_json IS '추천 시점 시세 스냅샷 JSON';
COMMENT ON COLUMN order_approval_workflow.risk_snapshot_json IS '추천 시점 리스크/자금관리 스냅샷 JSON';
COMMENT ON COLUMN order_approval_workflow.news_context_refs_json IS '추천 시점 뉴스/RAG 컨텍스트 참조 목록 JSON';
COMMENT ON COLUMN order_approval_workflow.assistant_snapshot_json IS '추천 시점 RAG 보조 요약 스냅샷 JSON';
COMMENT ON COLUMN order_approval_workflow.trace_id IS '추천/승인/주문 흐름 추적 ID';
COMMENT ON COLUMN order_approval_workflow.created_at IS '워크플로 생성 시각';
COMMENT ON COLUMN order_approval_workflow.updated_at IS '워크플로 최종 갱신 시각';

COMMENT ON TABLE order_approval_event_log IS '주문 승인 파이프라인 단계 전이/요청/결과 감사 로그';
COMMENT ON COLUMN order_approval_event_log.id IS '주문 승인 이벤트 로그 PK';
COMMENT ON COLUMN order_approval_event_log.workflow_id IS '대상 워크플로 ID';
COMMENT ON COLUMN order_approval_event_log.event_type IS '이벤트 유형(추천생성/승인/반려/주문요청/주문결과 등)';
COMMENT ON COLUMN order_approval_event_log.from_stage IS '전이 이전 단계';
COMMENT ON COLUMN order_approval_event_log.to_stage IS '전이 이후 단계';
COMMENT ON COLUMN order_approval_event_log.actor IS '수행 주체(관리자/시스템)';
COMMENT ON COLUMN order_approval_event_log.actor_role IS '수행 주체 역할(ADMIN/SYSTEM)';
COMMENT ON COLUMN order_approval_event_log.reason IS '수행 사유/메모';
COMMENT ON COLUMN order_approval_event_log.success IS '이벤트 처리 성공 여부';
COMMENT ON COLUMN order_approval_event_log.error_code IS '실패 코드';
COMMENT ON COLUMN order_approval_event_log.request_json IS '입력 요청 스냅샷 JSON';
COMMENT ON COLUMN order_approval_event_log.response_json IS '처리 결과 스냅샷 JSON';
COMMENT ON COLUMN order_approval_event_log.trace_id IS '추적 ID';
COMMENT ON COLUMN order_approval_event_log.created_at IS '이벤트 생성 시각';

COMMENT ON INDEX idx_order_approval_workflow_stage_updated IS '단계별 최신 워크플로 조회 인덱스';
COMMENT ON INDEX idx_order_approval_workflow_country_stage IS '국가/단계별 최신 워크플로 조회 인덱스';
COMMENT ON INDEX idx_order_approval_workflow_signal IS '시그널 기준 워크플로 재현 조회 인덱스';
COMMENT ON INDEX idx_order_approval_workflow_trace IS 'trace_id 기준 워크플로 추적 인덱스';
COMMENT ON INDEX idx_order_approval_workflow_paper_order IS '모의주문 연결 워크플로 조회 인덱스';
COMMENT ON INDEX idx_order_approval_event_workflow IS '워크플로별 이벤트 감사 로그 조회 인덱스';
COMMENT ON INDEX idx_order_approval_event_trace IS 'trace_id 기준 이벤트 감사 로그 조회 인덱스';
COMMENT ON INDEX idx_order_approval_event_type IS '이벤트 유형별 최신 감사 로그 조회 인덱스';

/*
 * PROMPT-8-REBUILD 5차
 * - 경량 RAG/LLM 보조 계층 감사 로그 테이블 추가
 */

CREATE TABLE IF NOT EXISTS assistant_rag_audit_log (
    id BIGSERIAL PRIMARY KEY,
    request_scope VARCHAR(32) NOT NULL,
    request_key VARCHAR(120),
    signal_id VARCHAR(64),
    asset_code VARCHAR(32),
    trace_id VARCHAR(64),
    model_version VARCHAR(64),
    prompt_version VARCHAR(64),
    rag_context_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    latency_ms_total BIGINT,
    fallback_applied BOOLEAN NOT NULL DEFAULT FALSE,
    success BOOLEAN NOT NULL DEFAULT TRUE,
    error_code VARCHAR(80),
    output_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_assistant_rag_audit_scope_time
    ON assistant_rag_audit_log (request_scope, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_assistant_rag_audit_trace
    ON assistant_rag_audit_log (trace_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_assistant_rag_audit_signal
    ON assistant_rag_audit_log (signal_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_assistant_rag_audit_asset
    ON assistant_rag_audit_log (asset_code, created_at DESC);

COMMENT ON TABLE assistant_rag_audit_log IS '경량 RAG/LLM 보조 분석 호출 감사 로그';
COMMENT ON COLUMN assistant_rag_audit_log.id IS 'RAG 감사 로그 PK';
COMMENT ON COLUMN assistant_rag_audit_log.request_scope IS '요청 범위(SIGNAL_DETAIL, TRACE_DETAIL 등)';
COMMENT ON COLUMN assistant_rag_audit_log.request_key IS '요청 식별키(signal_id 또는 trace_id 등)';
COMMENT ON COLUMN assistant_rag_audit_log.signal_id IS '연결된 시그널 ID(선택)';
COMMENT ON COLUMN assistant_rag_audit_log.asset_code IS '연결된 자산 코드(선택)';
COMMENT ON COLUMN assistant_rag_audit_log.trace_id IS '요청/배치 trace_id';
COMMENT ON COLUMN assistant_rag_audit_log.model_version IS '경량 모델/템플릿 버전';
COMMENT ON COLUMN assistant_rag_audit_log.prompt_version IS '프롬프트/출력 스키마 버전';
COMMENT ON COLUMN assistant_rag_audit_log.rag_context_refs_json IS 'RAG 컨텍스트 참조 목록(JSON 배열)';
COMMENT ON COLUMN assistant_rag_audit_log.latency_ms_total IS '전체 보조 분석 처리 지연(ms)';
COMMENT ON COLUMN assistant_rag_audit_log.fallback_applied IS '모델 실패/지연/비활성화로 fallback 적용 여부';
COMMENT ON COLUMN assistant_rag_audit_log.success IS '보조 분석 처리 성공 여부';
COMMENT ON COLUMN assistant_rag_audit_log.error_code IS '실패/폴백 원인 코드';
COMMENT ON COLUMN assistant_rag_audit_log.output_json IS '구조화 보조 분석 결과 JSON';
COMMENT ON COLUMN assistant_rag_audit_log.created_at IS '레코드 생성 시각';

COMMENT ON INDEX idx_assistant_rag_audit_scope_time IS '요청 범위별 최신 RAG 감사 조회 인덱스';
COMMENT ON INDEX idx_assistant_rag_audit_trace IS 'trace_id 기반 RAG 감사 추적 인덱스';
COMMENT ON INDEX idx_assistant_rag_audit_signal IS 'signal_id 기반 RAG 감사 추적 인덱스';
COMMENT ON INDEX idx_assistant_rag_audit_asset IS 'asset_code 기반 RAG 감사 추적 인덱스';

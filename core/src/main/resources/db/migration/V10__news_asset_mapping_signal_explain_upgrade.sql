/*
 * 3차 뉴스-종목 매핑 근거 저장 + 시그널 설명/감사 확장
 * - news_asset_link 근거 필드 추가
 * - trading_signal 설명/근거/정렬/신선도 JSON 필드 추가
 * - signal_audit_log 상세 설명 필드 추가
 * - COMMENT ON TABLE/COLUMN/INDEX 반영
 */

ALTER TABLE news_asset_link
    ADD COLUMN IF NOT EXISTS link_confidence NUMERIC(5, 4) NOT NULL DEFAULT 0.5,
    ADD COLUMN IF NOT EXISTS link_method VARCHAR(16) NOT NULL DEFAULT 'RULE',
    ADD COLUMN IF NOT EXISTS keyword_hits_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS ticker_alias_hit VARCHAR(200),
    ADD COLUMN IF NOT EXISTS theme_match_score NUMERIC(5, 4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS event_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS impact_direction VARCHAR(16),
    ADD COLUMN IF NOT EXISTS impact_horizon VARCHAR(16),
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);

UPDATE news_asset_link
SET link_confidence = COALESCE(link_confidence, confidence, 0.5),
    link_method = COALESCE(NULLIF(link_method, ''), 'RULE'),
    keyword_hits_json = COALESCE(keyword_hits_json, '{}'::jsonb),
    theme_match_score = COALESCE(theme_match_score, 0);

CREATE INDEX IF NOT EXISTS idx_news_asset_link_news_asset_type
    ON news_asset_link (news_id, asset_code, link_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_news_asset_link_event_scope
    ON news_asset_link (asset_code, event_type, impact_direction, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_news_asset_link_trace
    ON news_asset_link (trace_id);

ALTER TABLE trading_signal
    ADD COLUMN IF NOT EXISTS top_positive_factors_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS top_negative_factors_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS explain_text TEXT,
    ADD COLUMN IF NOT EXISTS news_alignment_result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS data_freshness_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS dedup_result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS rag_context_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE signal_audit_log
    ADD COLUMN IF NOT EXISTS top_positive_factors_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS top_negative_factors_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS explain_text TEXT,
    ADD COLUMN IF NOT EXISTS news_alignment_result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS data_freshness_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS dedup_result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS rag_context_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON TABLE news_asset_link IS '뉴스-자산 매핑 결과 및 근거 저장 테이블';
COMMENT ON COLUMN news_asset_link.link_confidence IS '뉴스-자산 매핑 링크 신뢰도(0~1)';
COMMENT ON COLUMN news_asset_link.link_method IS '매핑 방법(RULE, DICT, NER, LLM, HYBRID)';
COMMENT ON COLUMN news_asset_link.keyword_hits_json IS '별칭/테마/이벤트 키워드 매칭 근거 JSON';
COMMENT ON COLUMN news_asset_link.ticker_alias_hit IS '실제 매칭된 티커/별칭 문자열';
COMMENT ON COLUMN news_asset_link.theme_match_score IS '테마 키워드 매칭 점수(0~1)';
COMMENT ON COLUMN news_asset_link.event_type IS '이벤트 유형(EARNINGS, REGULATION, ORDER, INCIDENT, SUPPLY_CHAIN, MACRO 등)';
COMMENT ON COLUMN news_asset_link.impact_direction IS '영향 방향(POSITIVE, NEGATIVE, MIXED, NEUTRAL)';
COMMENT ON COLUMN news_asset_link.impact_horizon IS '영향 기간(INTRADAY, SHORT, MID, LONG)';
COMMENT ON COLUMN news_asset_link.trace_id IS '매핑 생성 trace_id';

COMMENT ON COLUMN trading_signal.reason_json IS '시그널 생성 전체 근거 요약 JSON';
COMMENT ON COLUMN trading_signal.top_positive_factors_json IS '상위 긍정 요인 목록 JSON';
COMMENT ON COLUMN trading_signal.top_negative_factors_json IS '상위 부정 요인 목록 JSON';
COMMENT ON COLUMN trading_signal.explain_text IS '상세보기용 설명 텍스트(왜 WATCH/HOLD/BUY인지)';
COMMENT ON COLUMN trading_signal.news_alignment_result_json IS '뉴스-가격 시간정렬 검증 결과 JSON';
COMMENT ON COLUMN trading_signal.data_freshness_json IS '입력 데이터 신선도(뉴스/시세/거래량) JSON';
COMMENT ON COLUMN trading_signal.dedup_result_json IS '중복기사/중복링크 처리 결과 JSON';
COMMENT ON COLUMN trading_signal.rag_context_refs_json IS '향후 RAG 연동용 참조 컨텍스트 ID 목록(JSON 배열, 현재 빈값 허용)';

COMMENT ON COLUMN signal_audit_log.top_positive_factors_json IS '상위 긍정 요인 목록 JSON';
COMMENT ON COLUMN signal_audit_log.top_negative_factors_json IS '상위 부정 요인 목록 JSON';
COMMENT ON COLUMN signal_audit_log.explain_text IS '감사용 설명 텍스트';
COMMENT ON COLUMN signal_audit_log.news_alignment_result_json IS '뉴스-가격 시간정렬 검증 결과 JSON';
COMMENT ON COLUMN signal_audit_log.data_freshness_json IS '입력 데이터 신선도 JSON';
COMMENT ON COLUMN signal_audit_log.dedup_result_json IS '중복기사/중복링크 처리 결과 JSON';
COMMENT ON COLUMN signal_audit_log.rag_context_refs_json IS '향후 RAG 연동용 참조 컨텍스트 목록(JSON 배열)';

COMMENT ON INDEX idx_news_asset_link_news_asset_type IS '뉴스+자산+링크유형 기준 최신 링크 조회/업서트 인덱스';
COMMENT ON INDEX idx_news_asset_link_event_scope IS '이벤트/방향 기반 매핑 진단 인덱스';
COMMENT ON INDEX idx_news_asset_link_trace IS 'trace_id 기반 매핑 상세 추적 인덱스';

CREATE TABLE IF NOT EXISTS system_feature_toggle (
    id BIGSERIAL PRIMARY KEY,
    feature_key VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    scope_type VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
    scope_value VARCHAR(120),
    reason TEXT,
    updated_by VARCHAR(80),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    trace_id VARCHAR(64)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_feature_toggle
    ON system_feature_toggle (feature_key, scope_type, COALESCE(scope_value, ''));
CREATE INDEX IF NOT EXISTS idx_feature_toggle_enabled
    ON system_feature_toggle (enabled, feature_key, scope_type);
CREATE INDEX IF NOT EXISTS idx_feature_toggle_trace_id
    ON system_feature_toggle (trace_id, updated_at DESC);

COMMENT ON TABLE system_feature_toggle IS '운영 킬스위치/전략 비활성화 설정 테이블';
COMMENT ON COLUMN system_feature_toggle.id IS '토글 PK';
COMMENT ON COLUMN system_feature_toggle.feature_key IS '기능 키(SIGNAL_GENERATION, PAPER_TRADING, SCALP_ENGINE 등)';
COMMENT ON COLUMN system_feature_toggle.enabled IS '기능 활성 여부';
COMMENT ON COLUMN system_feature_toggle.scope_type IS '적용 범위(GLOBAL, COUNTRY, THEME, ASSET)';
COMMENT ON COLUMN system_feature_toggle.scope_value IS '범위 값(국가코드/테마코드/자산코드)';
COMMENT ON COLUMN system_feature_toggle.reason IS '변경 사유';
COMMENT ON COLUMN system_feature_toggle.updated_by IS '변경자';
COMMENT ON COLUMN system_feature_toggle.updated_at IS '변경 시각';
COMMENT ON COLUMN system_feature_toggle.trace_id IS '요청 추적 ID';

COMMENT ON INDEX uq_feature_toggle IS '기능키+범위 유일성 보장';
COMMENT ON INDEX idx_feature_toggle_enabled IS '활성 토글 빠른 조회';
COMMENT ON INDEX idx_feature_toggle_trace_id IS 'trace_id 기반 상세 조회';

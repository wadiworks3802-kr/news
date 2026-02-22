/*
 * 2차 유니버스/선정/중복억제 정책 기반 확장
 * - asset_universe 정책/레이어/신선도 추적 필드 추가
 * - 진단/패널 선택용 인덱스 보강
 * - COMMENT ON TABLE/COLUMN 반영
 */

ALTER TABLE asset_universe
    ADD COLUMN IF NOT EXISTS theme_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS is_trade_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS universe_layer VARCHAR(24),
    ADD COLUMN IF NOT EXISTS diversity_score NUMERIC(6, 4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS selection_reason TEXT,
    ADD COLUMN IF NOT EXISTS is_user_watch BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS dup_exposure_cooldown_minutes INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_signal_generated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_quote_received_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_news_linked_at TIMESTAMPTZ;

UPDATE asset_universe
SET theme_code = COALESCE(NULLIF(theme_code, ''), NULLIF(UPPER(REPLACE(REPLACE(COALESCE(theme, ''), ' ', '_'), '-', '_')), '')),
    is_trade_enabled = COALESCE(is_trade_enabled, active, TRUE),
    universe_layer = COALESCE(universe_layer, CASE WHEN COALESCE(is_core_asset, FALSE) THEN 'CORE' ELSE 'DISCOVERY' END),
    diversity_score = COALESCE(diversity_score, 0),
    is_user_watch = COALESCE(is_user_watch, is_watchlist_asset, FALSE),
    dup_exposure_cooldown_minutes = COALESCE(dup_exposure_cooldown_minutes, 0);

CREATE INDEX IF NOT EXISTS idx_asset_universe_layer_theme_code
    ON asset_universe (country, universe_layer, theme_code, selection_score DESC, display_weight DESC);
CREATE INDEX IF NOT EXISTS idx_asset_universe_trade_enabled
    ON asset_universe (country, is_trade_enabled, active, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_asset_universe_freshness
    ON asset_universe (last_quote_received_at DESC, last_signal_generated_at DESC, last_news_linked_at DESC);

COMMENT ON TABLE asset_universe IS '전략패널/유니버스 선정을 위한 자산 풀 및 선정/노출 정책 상태';
COMMENT ON COLUMN asset_universe.theme_code IS '정규화된 테마 코드(AI, SEMICONDUCTOR, ENERGY 등)';
COMMENT ON COLUMN asset_universe.is_trade_enabled IS '전략 엔진/모의매매에서 거래 후보로 사용할 수 있는지 여부';
COMMENT ON COLUMN asset_universe.universe_layer IS '유니버스 레이어(CORE, WATCHLIST, THEME_LEADER, DISCOVERY)';
COMMENT ON COLUMN asset_universe.diversity_score IS '다양성 점수(패밀리/테마/레이어 편중 억제 결과)';
COMMENT ON COLUMN asset_universe.selection_reason IS '유니버스 선정 근거 요약(진단/패널 메타용)';
COMMENT ON COLUMN asset_universe.is_user_watch IS '사용자 직접 지정 관심종목 여부(프론트/정책용 별도 플래그)';
COMMENT ON COLUMN asset_universe.dup_exposure_cooldown_minutes IS '중복 노출 억제를 위한 쿨다운 분 단위';
COMMENT ON COLUMN asset_universe.last_signal_generated_at IS '가장 최근 시그널 생성 시각';
COMMENT ON COLUMN asset_universe.last_quote_received_at IS '가장 최근 시세 수신 시각(quote)';
COMMENT ON COLUMN asset_universe.last_news_linked_at IS '가장 최근 뉴스-자산 링크 시각';

COMMENT ON INDEX idx_asset_universe_layer_theme_code IS '국가/레이어/테마코드 기반 패널 후보 조회 인덱스';
COMMENT ON INDEX idx_asset_universe_trade_enabled IS '거래 가능 자산 필터/정렬 인덱스';
COMMENT ON INDEX idx_asset_universe_freshness IS '시세/시그널/뉴스 신선도 진단 인덱스';

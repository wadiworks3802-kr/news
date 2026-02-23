ALTER TABLE IF EXISTS asset_universe
    ADD COLUMN IF NOT EXISTS country_code VARCHAR(5);

ALTER TABLE IF EXISTS asset_universe
    ADD COLUMN IF NOT EXISTS strategy_scope VARCHAR(64);

ALTER TABLE IF EXISTS asset_universe
    ADD COLUMN IF NOT EXISTS last_panel_exposed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE IF EXISTS asset_universe
    ADD COLUMN IF NOT EXISTS panel_exposure_count_24h INTEGER DEFAULT 0 NOT NULL;

UPDATE asset_universe
SET country_code = country
WHERE country_code IS NULL OR TRIM(country_code) = '';

UPDATE asset_universe
SET strategy_scope = 'ALL'
WHERE strategy_scope IS NULL OR TRIM(strategy_scope) = '';

UPDATE asset_universe
SET panel_exposure_count_24h = 0
WHERE panel_exposure_count_24h IS NULL;

COMMENT ON COLUMN asset_universe.country_code IS '정규화된 국가 코드(조회/진단/정책 필터용)';
COMMENT ON COLUMN asset_universe.strategy_scope IS '전략 패널 우선 노출 범위(SCALP, SWING, POSITION, DISCOVERY, SCALP_SWING, ALL)';
COMMENT ON COLUMN asset_universe.last_panel_exposed_at IS '가장 최근 전략 패널 노출 시각(중복 억제용)';
COMMENT ON COLUMN asset_universe.panel_exposure_count_24h IS '최근 24시간 전략 패널 노출 누적 횟수';

CREATE INDEX IF NOT EXISTS idx_asset_universe_country_theme_scope
    ON asset_universe(country_code, theme_code, strategy_scope);

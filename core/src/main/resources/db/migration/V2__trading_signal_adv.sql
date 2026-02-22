CREATE TABLE asset_universe (
    asset_code VARCHAR(32) PRIMARY KEY,
    asset_name VARCHAR(160) NOT NULL,
    country VARCHAR(5) NOT NULL,
    theme VARCHAR(64),
    sector VARCHAR(64),
    asset_type VARCHAR(16) NOT NULL DEFAULT 'STOCK',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    liquidity_score NUMERIC(8, 4) NOT NULL DEFAULT 0.5,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE market_price_bar (
    id BIGSERIAL PRIMARY KEY,
    asset_code VARCHAR(32) NOT NULL REFERENCES asset_universe(asset_code),
    bar_time TIMESTAMPTZ NOT NULL,
    timeframe VARCHAR(8) NOT NULL,
    open_price NUMERIC(18, 6) NOT NULL,
    high_price NUMERIC(18, 6) NOT NULL,
    low_price NUMERIC(18, 6) NOT NULL,
    close_price NUMERIC(18, 6) NOT NULL,
    volume NUMERIC(24, 4) NOT NULL DEFAULT 0,
    provider_name VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_market_price_bar UNIQUE (asset_code, timeframe, bar_time)
);

CREATE TABLE market_quote_snapshot (
    id BIGSERIAL PRIMARY KEY,
    asset_code VARCHAR(32) NOT NULL REFERENCES asset_universe(asset_code),
    snapshot_utc TIMESTAMPTZ NOT NULL,
    last_price NUMERIC(18, 6),
    change_pct NUMERIC(8, 4),
    bid_price NUMERIC(18, 6),
    ask_price NUMERIC(18, 6),
    bid_size NUMERIC(24, 4),
    ask_size NUMERIC(24, 4),
    spread_pct NUMERIC(8, 4),
    volume NUMERIC(24, 4),
    provider_name VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE news_asset_link (
    id BIGSERIAL PRIMARY KEY,
    news_id VARCHAR(64) NOT NULL REFERENCES news(id),
    asset_code VARCHAR(32) NOT NULL REFERENCES asset_universe(asset_code),
    link_type VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
    confidence NUMERIC(5, 4) NOT NULL DEFAULT 0.5,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_news_asset_link UNIQUE (news_id, asset_code, link_type)
);

CREATE TABLE trading_signal (
    id VARCHAR(64) PRIMARY KEY,
    asset_code VARCHAR(32) NOT NULL REFERENCES asset_universe(asset_code),
    country VARCHAR(5) NOT NULL,
    theme VARCHAR(64),
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    signal_window VARCHAR(16) NOT NULL DEFAULT '1h',
    action VARCHAR(24) NOT NULL,
    market_regime VARCHAR(24) NOT NULL DEFAULT 'MIXED',
    good_news_probability NUMERIC(5, 4) NOT NULL DEFAULT 0.5,
    bad_news_probability NUMERIC(5, 4) NOT NULL DEFAULT 0.5,
    news_confidence NUMERIC(5, 4) NOT NULL DEFAULT 0.5,
    chart_confidence NUMERIC(5, 4) NOT NULL DEFAULT 0.5,
    combined_confidence NUMERIC(5, 4) NOT NULL DEFAULT 0.5,
    weekly_context_score NUMERIC(8, 4) NOT NULL DEFAULT 0,
    scalp_signal_score NUMERIC(8, 4) NOT NULL DEFAULT 0,
    swing_signal_score NUMERIC(8, 4) NOT NULL DEFAULT 0,
    position_management_signal NUMERIC(8, 4) NOT NULL DEFAULT 0,
    discovery_score NUMERIC(8, 4) NOT NULL DEFAULT 0,
    sell_pressure_detected BOOLEAN NOT NULL DEFAULT FALSE,
    sell_pressure_is_negative BOOLEAN NOT NULL DEFAULT FALSE,
    buy_pressure_detected BOOLEAN NOT NULL DEFAULT FALSE,
    buy_pressure_is_positive BOOLEAN NOT NULL DEFAULT FALSE,
    volume_regime_same BOOLEAN NOT NULL DEFAULT FALSE,
    avg_down_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    avg_down_stage INTEGER NOT NULL DEFAULT 0,
    avg_down_reason TEXT,
    avg_down_next_buy_ratio NUMERIC(8, 4),
    reanalysis_lock_required BOOLEAN NOT NULL DEFAULT FALSE,
    reanalysis_lock_until TIMESTAMPTZ,
    risk_checks JSONB NOT NULL DEFAULT '[]'::jsonb,
    blocked_reason TEXT,
    reason_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    model_version VARCHAR(64) NOT NULL DEFAULT 'rule-heuristic-v2',
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE strategy_run (
    id BIGSERIAL PRIMARY KEY,
    run_type VARCHAR(24) NOT NULL,
    scope_country VARCHAR(5),
    scope_theme VARCHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    processed_count INTEGER NOT NULL DEFAULT 0,
    created_signal_count INTEGER NOT NULL DEFAULT 0,
    report_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE paper_trade_order (
    id BIGSERIAL PRIMARY KEY,
    asset_code VARCHAR(32) NOT NULL REFERENCES asset_universe(asset_code),
    signal_id VARCHAR(64) REFERENCES trading_signal(id),
    order_side VARCHAR(8) NOT NULL,
    order_type VARCHAR(16) NOT NULL DEFAULT 'MARKET',
    request_ratio NUMERIC(8, 4),
    request_amount NUMERIC(18, 2),
    request_price NUMERIC(18, 6),
    status VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
    blocked_reason TEXT,
    risk_checks JSONB NOT NULL DEFAULT '[]'::jsonb,
    executed_price NUMERIC(18, 6),
    executed_amount NUMERIC(18, 2),
    executed_at TIMESTAMPTZ,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE paper_trade_position (
    id BIGSERIAL PRIMARY KEY,
    asset_code VARCHAR(32) NOT NULL UNIQUE REFERENCES asset_universe(asset_code),
    quantity NUMERIC(20, 6) NOT NULL DEFAULT 0,
    avg_price NUMERIC(18, 6) NOT NULL DEFAULT 0,
    invested_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    current_price NUMERIC(18, 6),
    pnl_pct NUMERIC(8, 4),
    avg_down_stage INTEGER NOT NULL DEFAULT 0,
    buy_lock BOOLEAN NOT NULL DEFAULT FALSE,
    lock_reason VARCHAR(120),
    lock_until TIMESTAMPTZ,
    last_reanalysis_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE market_provider_job (
    id BIGSERIAL PRIMARY KEY,
    provider_name VARCHAR(64) NOT NULL,
    asset_code VARCHAR(32),
    job_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    attempt INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    scheduled_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE strategy_config (
    config_key VARCHAR(120) PRIMARY KEY,
    config_value TEXT NOT NULL,
    value_type VARCHAR(24) NOT NULL DEFAULT 'STRING',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_asset_universe_country_theme ON asset_universe (country, theme);
CREATE INDEX idx_market_price_bar_asset_tf_time ON market_price_bar (asset_code, timeframe, bar_time DESC);
CREATE INDEX idx_market_quote_snapshot_asset_time ON market_quote_snapshot (asset_code, snapshot_utc DESC);
CREATE INDEX idx_news_asset_link_asset_created ON news_asset_link (asset_code, created_at DESC);
CREATE INDEX idx_trading_signal_country_action_gen ON trading_signal (country, action, generated_at DESC);
CREATE INDEX idx_trading_signal_asset_gen ON trading_signal (asset_code, generated_at DESC);
CREATE INDEX idx_trading_signal_combined_conf ON trading_signal (combined_confidence DESC);
CREATE INDEX idx_strategy_run_type_started ON strategy_run (run_type, started_at DESC);
CREATE INDEX idx_paper_trade_order_status_created ON paper_trade_order (status, created_at DESC);
CREATE INDEX idx_paper_trade_position_lock_until ON paper_trade_position (buy_lock, lock_until);
CREATE INDEX idx_market_provider_job_sched ON market_provider_job (provider_name, status, scheduled_at DESC);


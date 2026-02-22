CREATE TABLE source (
    sid VARCHAR(64) PRIMARY KEY,
    country VARCHAR(5) NOT NULL,
    source_grade VARCHAR(2) NOT NULL DEFAULT 'P1',
    priority INTEGER NOT NULL,
    allow_fetch BOOLEAN NOT NULL DEFAULT TRUE,
    allow_store_raw BOOLEAN NOT NULL DEFAULT FALSE,
    allow_store_derived BOOLEAN NOT NULL DEFAULT TRUE,
    cache_ttl_seconds INTEGER NOT NULL,
    license_policy VARCHAR(120) NOT NULL,
    robots_policy VARCHAR(120) NOT NULL,
    endpoint_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE news (
    id VARCHAR(64) PRIMARY KEY,
    sid VARCHAR(64) NOT NULL REFERENCES source(sid),
    country VARCHAR(5) NOT NULL,
    lang VARCHAR(8) NOT NULL,
    category VARCHAR(16) NOT NULL,
    url TEXT NOT NULL,
    title_raw TEXT NOT NULL,
    body_raw TEXT NOT NULL,
    pub_utc TIMESTAMPTZ,
    fetch_utc TIMESTAMPTZ NOT NULL,
    license VARCHAR(120) NOT NULL,
    robots BOOLEAN NOT NULL,
    ttl INTEGER NOT NULL,
    url_norm TEXT NOT NULL,
    title_ko TEXT,
    summary_ko TEXT,
    evidence_spans JSONB NOT NULL DEFAULT '[]'::jsonb,
    content_hash VARCHAR(64) NOT NULL,
    simhash64 BIGINT,
    dedup_group_id VARCHAR(64),
    trust_score NUMERIC(5, 4) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE fetch_job (
    id BIGSERIAL PRIMARY KEY,
    sid VARCHAR(64) NOT NULL REFERENCES source(sid),
    status VARCHAR(24) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    scheduled_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE insight_log (
    id BIGSERIAL PRIMARY KEY,
    news_id VARCHAR(64) NOT NULL REFERENCES news(id),
    category VARCHAR(16) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    content TEXT NOT NULL,
    risk_flag BOOLEAN NOT NULL DEFAULT FALSE,
    trace_id VARCHAR(64) NOT NULL
);

CREATE UNIQUE INDEX uq_news_url_norm ON news (url_norm);
CREATE INDEX idx_news_category_country_pub ON news (category, country, pub_utc DESC);
CREATE INDEX idx_news_content_hash ON news (content_hash);
CREATE INDEX idx_news_simhash64 ON news (simhash64);
CREATE INDEX idx_news_title_ko_gin ON news USING gin (to_tsvector('simple', COALESCE(title_ko, '')));
CREATE INDEX idx_news_summary_ko_gin ON news USING gin (to_tsvector('simple', COALESCE(summary_ko, '')));
CREATE INDEX idx_news_created_at ON news (created_at);
CREATE INDEX idx_fetch_job_status_sched ON fetch_job (status, scheduled_at);
CREATE INDEX idx_insight_news_id ON insight_log (news_id);

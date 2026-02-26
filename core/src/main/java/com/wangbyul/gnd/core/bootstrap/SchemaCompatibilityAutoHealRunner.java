package com.wangbyul.gnd.core.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 운영/로컬 DB 드리프트가 남아 있어도 최소 컬럼 누락으로 앱이 즉시 깨지지 않도록
 * 기동 시 경량 스키마 보정 SQL을 시도한다.
 */
@Slf4j
@Component
public class SchemaCompatibilityAutoHealRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;

    public SchemaCompatibilityAutoHealRunner(
            JdbcTemplate jdbcTemplate,
            @Value("${app.schema.auto-heal.enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("schema auto-heal disabled");
            return;
        }

        apply("asset_universe.country_code",
                "ALTER TABLE IF EXISTS asset_universe ADD COLUMN IF NOT EXISTS country_code VARCHAR(5)");
        apply("asset_universe.strategy_scope",
                "ALTER TABLE IF EXISTS asset_universe ADD COLUMN IF NOT EXISTS strategy_scope VARCHAR(64)");
        apply("asset_universe.last_panel_exposed_at",
                "ALTER TABLE IF EXISTS asset_universe ADD COLUMN IF NOT EXISTS last_panel_exposed_at TIMESTAMP WITH TIME ZONE");
        apply("asset_universe.panel_exposure_count_24h",
                "ALTER TABLE IF EXISTS asset_universe ADD COLUMN IF NOT EXISTS panel_exposure_count_24h INTEGER DEFAULT 0 NOT NULL");
        apply("asset_universe.country_code_backfill",
                "UPDATE asset_universe SET country_code = country WHERE country_code IS NULL OR TRIM(country_code) = ''");
        apply("asset_universe.strategy_scope_backfill",
                "UPDATE asset_universe SET strategy_scope = 'ALL' WHERE strategy_scope IS NULL OR TRIM(strategy_scope) = ''");
        apply("asset_universe.panel_exposure_count_24h_backfill",
                "UPDATE asset_universe SET panel_exposure_count_24h = 0 WHERE panel_exposure_count_24h IS NULL");
    }

    private void apply(String step, String sql) {
        try {
            jdbcTemplate.execute(sql);
            log.info("schema auto-heal step={} success", step);
        } catch (Exception e) {
            // 보정 실패는 로그로만 남기고 앱 가용성을 우선한다.
            log.warn("schema auto-heal step={} failed: {}", step, e.getMessage());
        }
    }
}

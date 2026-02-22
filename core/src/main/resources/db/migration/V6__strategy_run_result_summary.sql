ALTER TABLE strategy_run
    ADD COLUMN IF NOT EXISTS result_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN strategy_run.result_summary_json IS
    '백테스트/전략 실행 결과 요약(JSON): 검증모드, OOS 검증, 비교 지표, 경고 포함';

ALTER TABLE trading_signal
    ADD COLUMN IF NOT EXISTS probability_reason_breakdown_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS pressure_reason_json JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN trading_signal.probability_reason_breakdown_json IS
    'RULE_V1 호재/악재 확률 계산 근거(표본 수, 신뢰도 필터, 감쇠, 임계치) JSON';
COMMENT ON COLUMN trading_signal.pressure_reason_json IS
    'PRESSURE_RULESET_V1 연속 매수/매도 + 거래량 동일 판정 근거 JSON';

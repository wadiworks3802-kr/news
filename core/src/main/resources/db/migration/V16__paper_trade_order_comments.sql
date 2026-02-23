/*
 * PROMPT-8-REBUILD 8차
 * - 모의주문 승인 파이프라인 준비를 위한 paper_trade_order 메타데이터 COMMENT 보강
 */

COMMENT ON TABLE paper_trade_order IS '관리자 승인 기반 주문 파이프라인과 연계되는 모의주문 실행/결과 기록 테이블';
COMMENT ON COLUMN paper_trade_order.id IS '모의주문 PK';
COMMENT ON COLUMN paper_trade_order.asset_code IS '주문 대상 자산 코드';
COMMENT ON COLUMN paper_trade_order.signal_id IS '연계된 원본 시그널 ID';
COMMENT ON COLUMN paper_trade_order.order_side IS '주문 방향(BUY/SELL)';
COMMENT ON COLUMN paper_trade_order.order_type IS '주문 유형(MARKET 등)';
COMMENT ON COLUMN paper_trade_order.request_ratio IS '리스크 정책 기준 요청 비중';
COMMENT ON COLUMN paper_trade_order.request_amount IS '요청 금액';
COMMENT ON COLUMN paper_trade_order.request_price IS '요청 가격(지정가/참고가)';
COMMENT ON COLUMN paper_trade_order.status IS '모의주문 상태(REQUESTED/FILLED/BLOCKED 등)';
COMMENT ON COLUMN paper_trade_order.blocked_reason IS '차단 사유(리스크 정책/락 등)';
COMMENT ON COLUMN paper_trade_order.risk_checks IS '리스크 점검 결과 목록 JSON';
COMMENT ON COLUMN paper_trade_order.executed_price IS '체결 가격(모의)';
COMMENT ON COLUMN paper_trade_order.executed_amount IS '체결 금액(모의)';
COMMENT ON COLUMN paper_trade_order.executed_at IS '체결 시각(모의)';
COMMENT ON COLUMN paper_trade_order.trace_id IS '추천/승인/주문 추적 ID';
COMMENT ON COLUMN paper_trade_order.created_at IS '모의주문 생성 시각';

COMMENT ON INDEX idx_paper_trade_order_status_created IS '모의주문 상태/생성시각 기준 최신 조회 인덱스';

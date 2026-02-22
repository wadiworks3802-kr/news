# 배포 검증 체크리스트 (운영 진단 재현용)

작성 목적: "화면이 뜸 = 정상" 판단을 방지하고, API/DB/감사로그 기준으로 운영 상태를 검증한다.

## 1. 헬스체크
- `GET /actuator/health` 응답이 `UP` 인지 확인
- `trace_id`는 actuator 기본 응답에는 없을 수 있으므로, 아래 진단/비즈니스 API에서 확인

## 2. 시장데이터 수집 상태 진단
- `GET /api/admin/diagnostics/market-collection/summary`
- 확인 항목:
  - `provider_name`
  - `is_delayed`
  - `warnings`
  - `warning_flags.mock_provider_detected`
  - `recent_quote_provider_distribution`
  - `recent_provider_job_distribution`
  - `feature_toggle_status`
  - `trace_propagation`
  - `market_collection_audit_status`
  - `deployment_verification_checklist`

## 3. Mock 사용 여부 강제 확인
- `provider_name == "MOCK"` 또는 `provider_names`에 `MOCK` 포함 시 운영 경고로 판단
- `warning_flags.mock_provider_detected == true` 인지 확인
- `provider_runtime_config.allow_mock == false` 인 상태에서 `MOCK`가 감지되면 설정/운영 불일치로 분류

## 4. 전략/상세 API 응답 검증
- 전략 패널 API (`/api/insight/signals/scalp` 등)
  - `meta.trace_id`
  - `meta.provider_name`
  - `meta.is_delayed`
  - `meta.warnings`
- 상세 API (`/api/insight/signals/{signalId}`)
  - 동일 메타 필드 확인
  - `trace_id` 상호 연계 가능한지 확인

## 5. 감사 테이블 상태 확인 (DB)
- `market_provider_job`
- `api_response_audit`
- `market_data_gap_event`
- `market_data_quality_snapshot`
- TODO (후속 고도화):
  - 애플리케이션 WARN/ERROR 로그 원문 집계 테이블/API 연결
  - 배포 직후 smoke-check 결과 감사 테이블 저장

## 6. Feature Toggle / 실주문 보호
- `LIVE_TRADE` 기본 OFF 확인 (`config + toggle`)
- `AUTO_ORDER_WITH_ADMIN_APPROVAL`, `AUTO_ORDER_FULLY_AUTOMATED` 기본 OFF 확인
- `RAG_ASSISTANT` 상태 확인

## 7. 배포 판정 최소 기준
- 헬스체크 `UP`
- 진단 API 응답 200
- `trace_id`가 전략/상세 API 응답에 포함
- Mock 사용 시 경고가 숨겨지지 않음
- 감사 테이블 기반 수집 상태/지연/실패 확인 가능

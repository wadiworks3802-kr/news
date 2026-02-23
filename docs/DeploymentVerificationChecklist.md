# 배포 검증 체크리스트 (운영/스테이징 최종 검수용)

작성 목적: "화면이 뜸 = 정상" 판단을 방지하고, 배포 전/후에 API/DB/감사로그/로그 기준으로 상태를 검증한다.

## 0. 배포 대상 확정 (배포 전 필수)
- 대상 브랜치: `feature/*` 또는 릴리즈 승인된 브랜치
- 배포 커밋 해시: `git rev-parse --short HEAD`
- 배포 대상 환경: `staging` 또는 `prod`
- 배포 시각(예정/실제): KST 기준 기록
- 배포 실행자/근거 문서 링크 기록

증거 예시:
- `git rev-parse --abbrev-ref HEAD`
- `git rev-parse HEAD`
- 배포 스크립트 실행 로그 요약 (`server-unpack-and-deploy.sh` / `server-build-restart.sh`)

## 1. 헬스체크
- `GET /actuator/health` 응답이 `UP` 인지 확인
- `trace_id`는 actuator 기본 응답에는 없을 수 있으므로, 아래 진단/비즈니스 API에서 확인
- 로컬 검증에서 Redis 미기동 시 `DOWN`이 발생할 수 있음 (운영/스테이징 판정과 분리하여 기록)

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

추가 확인:
- `provider_runtime_config.active_provider`
- `provider_runtime_config.allow_mock`
- `feature_toggle_status.effective`

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
  - `meta.duplicate_asset_rows == 0` (중복 완화 기준)
  - `meta.core_theme_filter_applied_count` (핵심분야 반영 여부)
- 상세 API (`/api/insight/signals/{signalId}`)
  - 동일 메타 필드 확인
  - `trace_id` 상호 연계 가능한지 확인
  - `decision_why`, `missing_requirements`, `change_conditions` 존재
  - `strategy_comparison`, `strategy_evidence`, `risk_guidance` 존재
  - `assistant_rag.rule_engine_action_locked == true` (RAG 역전 방지)

권장 샘플:
- `GET /api/insight/signals/scalp?country=KR&limit=5`
- `GET /api/insight/signals/swing?country=KR&theme=Semiconductor&limit=10`
- `GET /api/insight/discovery?country=KR&theme=Semiconductor&limit=10`
- `GET /api/insight/signals/{signalId}?assistant=true`

## 5. 뉴스 화면/썸네일 API 검증
- 뉴스 목록 API: `GET /api/news?...`
  - `thumbnail_url`, `thumbnail_source`, `thumbnail_status` 필드 존재
  - `trace_id` 존재
- 관리자 썸네일 진단: `GET /api/admin/diagnostics/news/thumbnails`
  - `success_rate`
  - `thumbnail_status_distribution`
  - `rendering_contract_check`
  - `failure_samples` (실패 원인 분리 가능)

## 6. 관리자 진단 패널 확인 (운영 품질 추적)
- `GET /api/admin/diagnostics/universe/diversity`
- `GET /api/admin/diagnostics/news-asset-mapping`
- `GET /api/admin/diagnostics/signals/confidence-distribution`
- `GET /api/admin/diagnostics/trace-detail?trace_id=...`

확인 항목:
- 유니버스 편중도/반복 노출/쿨다운 경고
- 뉴스-자산 매핑 품질 리포트 `latest`, `trend`
- 전략 액션 분포 / confidence bucket / duplicate exposure stats
- RAG 성공률/지연/fallback 분포
- trace 전파 섹션(`trace_propagation`, `trace_lookup_guide`)

## 7. 감사 테이블 상태 확인 (DB)
- `market_provider_job`
- `api_response_audit`
- `market_data_gap_event`
- `market_data_quality_snapshot`
- `news` (thumbnail metadata)
- `trading_signal` (signal 생성/갱신)
- `assistant_rag_audit_log` (RAG 성공/실패/fallback)
- TODO (후속 고도화):
  - 애플리케이션 WARN/ERROR 로그 원문 집계 테이블/API 연결
  - 배포 직후 smoke-check 결과 감사 테이블 저장

DB 요약 쿼리 예시(H2/Postgres 공통 의도):
- provider 분포
  - `SELECT provider_name, COUNT(*), MAX(snapshot_utc) FROM market_quote_snapshot GROUP BY provider_name;`
- thumbnail 상태
  - `SELECT thumbnail_status, thumbnail_source, COUNT(*) FROM news GROUP BY thumbnail_status, thumbnail_source;`
- signal 상태/최신시각
  - `SELECT action, COUNT(*), MAX(generated_at) FROM trading_signal GROUP BY action;`

## 8. Feature Toggle / 실주문 보호
- `LIVE_TRADE` 기본 OFF 확인 (`config + toggle`)
- `AUTO_ORDER_WITH_ADMIN_APPROVAL`, `AUTO_ORDER_FULLY_AUTOMATED` 기본 OFF 확인
- `RAG_ASSISTANT` 상태 확인
- 주문 승인 파이프라인 사용 시:
  - 승인 전 주문 요청 차단
  - `ORDER_EXECUTED`가 `PAPER_ONLY`로 기록
  - `trade_approval`/`paper_trade_order` trace 연결 확인

## 9. 배포 후 스모크 테스트 (필수)
- `/actuator/health`
- 핵심 API 200:
  - `/api/news`
  - `/api/insight/signals/scalp`
  - `/api/insight/signals/{signalId}`
  - `/api/admin/diagnostics/market-collection/summary`
- UI 주요 화면 수동 점검 (브라우저)
  - 글로벌 뉴스
  - `/assistant`
  - 관리자 진단/주문승인 패널
- DB 적재/갱신 확인
  - 수동 수집 또는 스케줄러 실행 후 `news`, `market_provider_job`, `trading_signal` 증가/갱신
- mock/실데이터 구분 상태 확인
- 관리자 진단 패널 지표 확인
- 서버 로그 ERROR/WARN 점검 (`journalctl -u gnd-api -u gnd-web -u gnd-batch`)

## 10. 배포 판정 최소 기준
- 헬스체크 `UP`
- 진단 API 응답 200
- `trace_id`가 전략/상세 API 응답에 포함
- Mock 사용 시 경고가 숨겨지지 않음
- 감사 테이블 기반 수집 상태/지연/실패 확인 가능
- 치명 오류(ERROR) 0건 또는 원인/영향도/우회책이 문서화됨
- 배포 버전 식별값(커밋 해시)과 배포 시각이 기록됨

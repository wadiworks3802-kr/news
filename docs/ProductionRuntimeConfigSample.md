# 운영 설정 샘플 (민감값 제외)

목적:
- 운영/스테이징 배포 전에 필수 런타임 설정 키를 빠르게 점검하기 위한 샘플.
- 실제 비밀값(API key, 계정 비밀번호, 토큰)은 포함하지 않는다.

## 1) API 서비스 환경변수 샘플 (systemd/docker 공통 의도)

```bash
# 프로필 / 포트
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080

# DB (예시: PostgreSQL)
SPRING_DATASOURCE_URL=jdbc:postgresql://<DB_HOST>:5432/gnd
SPRING_DATASOURCE_USERNAME=<DB_USER>
SPRING_DATASOURCE_PASSWORD=<SECRET>

# Redis
SPRING_DATA_REDIS_HOST=<REDIS_HOST>
SPRING_DATA_REDIS_PORT=6379

# 관리자 API
APP_SECURITY_ADMIN_API_KEY=<SECRET>

# CORS
APP_SECURITY_CORS_ALLOWLIST=https://<WEB_HOST>,https://<ADMIN_HOST>

# 번역/요약(예시)
APP_TRANSLATION_KO_ENABLED=true
APP_TRANSLATION_KO_TIMEOUT_SECONDS=4
APP_TRANSLATION_KO_MAX_CHARS=500

# 시장데이터 provider
APP_MARKET_PROVIDER_ACTIVE=toss
APP_MARKET_PROVIDER_ALLOW_MOCK=false
APP_MARKET_PROVIDER_FALLBACK_TO_MOCK_ON_FAILURE=false

# 실주문 보호 (준비 단계)
APP_TRADE_LIVE_ENABLED=false
```

## 2) Feature Toggle 기본 기대값 (운영/스테이징)

관리자 API: `GET /api/admin/feature-toggles`

기본 기대값:
- `LIVE_TRADE = false`
- `AUTO_ORDER_WITH_ADMIN_APPROVAL = false` (또는 제한 scope만 허용)
- `AUTO_ORDER_FULLY_AUTOMATED = false`
- `RAG_ASSISTANT = true|false` (운영 정책에 맞게 명시)

주의:
- `AUTO_ORDER_WITH_APPROVAL` 입력은 서비스에서 `AUTO_ORDER_WITH_ADMIN_APPROVAL`로 정규화(alias)되지만, 운영 문서/대시보드 표기는 canonical key 사용 권장.

## 3) 주문 승인 파이프라인 준비 단계 기대값

- 실주문 경로 비활성: `PAPER_ONLY`만 허용
- 승인 전 주문 요청 차단
- 승인/반려 사유 필수
- `paper_trade_order.trace_id`와 승인 워크플로 `trace_id` 연결 가능

## 4) 로컬/운영 차이 체크포인트

- 로컬:
  - `SPRING_PROFILES_ACTIVE=local`
  - H2 파일 DB 사용 가능
  - Redis 미기동 시 `/actuator/health = DOWN` 가능 (기능 API와 분리 해석)
  - MOCK provider 경고가 정상적으로 노출될 수 있음

- 운영/스테이징:
  - 실제 provider 사용 (`allow_mock=false`)
  - Redis/DB/Batch 모두 기동 상태에서 health + 진단 API 확인
  - mock 경고 노출 시 설정/연동 이상으로 분류

## 5) 배포 직후 빠른 점검 순서 (운영 담당자용)

1. `/actuator/health`
2. `/api/admin/diagnostics/market-collection/summary`
3. `/api/admin/diagnostics/news/thumbnails`
4. `/api/insight/signals/scalp?country=KR&limit=5`
5. `/api/insight/signals/{signalId}?assistant=true`
6. `/api/admin/diagnostics/signals/confidence-distribution`
7. `journalctl` WARN/ERROR 확인

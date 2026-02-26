# Project Handover (news / GND)

이 문서는 현재 `news` 프로젝트(글로벌 뉴스 대시보드 + AI 주식 비서 재구축 브랜치)의 상태를 새 세션/새 담당자가 빠르게 파악하고 바로 이어서 작업할 수 있도록 정리한 인수인계 문서입니다.

사용 원칙:
- 긴 로그/스크린샷은 이 문서에 붙이지 않고 경로/요약만 기록
- 상세 변경 이력은 `work_timeline.md`를 우선 참조
- 요구사항/정책/차수 범위는 `prompt.md`를 우선 참조

---

## 1) Project Metadata

- Project Name: Global News Dashboard + AI Stock Assistant (GND)
- Repository URL: `https://github.com/wadiworks3802-kr/news.git`
- Default Branch: `main`
- Working Branch: `feature/ai-stock-assistant-rebuild`
- Current Environment (Local/Dev/Staging/Prod): Local + 운영 서버(192.168.30.39) 혼합 운영
- Last Updated (YYYY-MM-DD HH:mm, TZ): 2026-02-23 (로컬 기준)
- Maintainer / Owner: 사용자(프로젝트 오너), Codex는 구현/정비 담당

---

## 2) Reference Documents (Must Read First)

- `prompt.md` (요구사항/정책/차수 기준)
- `work_timeline.md` (변경 이력 및 작업 증거 요약)
- `PROJECT_HANDOVER.md` (이 문서)
- `README.md` (로컬/서버 실행 및 운영 메모)
- `docs/DeploymentVerificationChecklist.md` (배포 검증 체크리스트)

추가 참고(대화/외부 기준):
- `PROMPT-REBUILD-V2` (채팅으로 전달된 재구축 프롬프트 기준)

읽기 순서(권장):
1. `prompt.md`
2. `work_timeline.md`
3. `PROJECT_HANDOVER.md`
4. 현재 차수 관련 코드/문서

---

## 3) Current Status Summary (One Screen)

- Current Phase / Sprint:
  - 재구축 작업 5차까지 완료 (1차~5차)
- What works now:
  - 뉴스 홈(카테고리/더보기/필터/자동갱신) 동작
  - 관리자 진단 탭 / Feature Toggle 조회
  - AI 비서 화면/전략패널/상세 API 기본 동작
  - 시장데이터 Provider 라우팅 골격 + 수동 수집 엔드포인트
  - `allowMock=false`일 때 mock fallback 차단 구조
  - 뉴스 썸네일 파싱/저장/렌더링(img 우선 + fallback)
  - 유니버스 재구성 + 핵심분야 반영 + 전략패널 중복 억제
  - RULE_V1 확률 계산 보강 및 50:50 강제 수렴 제거(데이터 부족 상태값 반환)
- What is partially implemented:
  - TossMCP / Kiwoom REST 실제 시세 Provider 연동 (현재 스텁/골격 중심)
  - 일부 전략 품질은 MOCK 시세 영향으로 실제성 낮음
  - 썸네일은 소스 데이터 부재 시 `EMPTY/DEFAULT`가 많음(특히 Google RSS KR)
- Known broken / blocked items:
  - 로컬 `/actuator/health`는 Redis 미기동 시 `DOWN(503)` (기능 API 자체와 별개)
  - 운영 배포 시 H2 스키마 드리프트가 있으면 AI 비서 쿼리(`trading_signal` 등) 실패 가능
  - 로컬 작업트리에 산출물/비관련 변경이 누적돼 `git status`/검색 체감 성능 저하 가능
- Highest risk area right now:
  - 운영/로컬 DB 스키마 정합성(H2, 마이그레이션 적용 누락)
  - MOCK provider 사용 중 실데이터로 오인할 위험 (응답/진단 경고는 추가됨)

---

## 4) Working Rules / Guardrails

필수 규칙만 요약합니다. 긴 전문은 `prompt.md` / `AGENTS.md` / 대화 지시 참조.

- Scope rule: 사용자가 지정한 차수 범위 밖 작업 금지
- Git rule: `main` 직접 작업 금지, 기능 단위 `commit + push`
- Data rule: DB schema 변경 시 `COMMENT` 필수
- Safety rule: 운영 화면/API에서 mock 데이터가 보이면 경고/표시 필수
- Trading rule: `LIVE_TRADE=false` 기본 유지 (명시 승인 전 활성화 금지)
- Trace rule: 분석/수집/API 응답에 `trace_id` 유지
- Reporting rule: 차수 종료 시 검증 증거 제출 (`health`, 로그 요약, API 샘플, DB 요약, provider/toggle 상태)

프로젝트별 추가 규칙:
- UI 대규모 개편은 차수 범위에 포함될 때만 수행
- 운영/서버 작업 전에는 현재 브랜치와 커밋 해시를 명시하고 진행
- `.data`, `.run`, `.deploy`, `*/bin` 등 산출물은 커밋 금지

---

## 5) Architecture / Module Overview

핵심 모듈만 요약합니다.

- `api`:
  - 뉴스/인사이트/관리자/진단/AI 비서 API
  - 전략패널/상세/RAG 보조/주문 승인 파이프라인 API
- `core`:
  - 도메인 엔티티/JPA 리포지토리
  - 시장데이터 Provider 라우팅/수집 서비스/유니버스 재구성 등 핵심 로직
- `collector`:
  - RSS/Atom 수집 및 본문/썸네일 파싱
- `batch`:
  - Quartz 기반 수집/정리/시장데이터 배치 잡
- `web`:
  - 정적 웹 UI (뉴스 홈 / AI 비서 / 관리자 진단)
- `nlp`:
  - 향후/일부 NLP 관련 모듈 골격 (프로젝트 구조상 존재)
- `scripts`:
  - 로컬/서버 배포 및 systemd 설치 스크립트

핵심 데이터 흐름 (요약):
1. `collector/batch`가 뉴스/시장데이터 수집 -> `core` 엔티티 저장
2. `api`가 뉴스 목록/전략패널/상세/진단 응답 생성 (`trace_id` 포함)
3. `web`이 API를 호출해 뉴스 홈/AI 비서/관리자 진단 렌더링

---

## 6) Runtime / Environment Setup

### Local

- Required JDK(s):
  - JDK 21 (Gradle/테스트 기준)
  - JDK 17 (보조/LTS)
- Build Tool:
  - Gradle Wrapper (`./gradlew.bat`)
- DB:
  - H2 (파일 기반, 프로젝트 내 `.data/gnd` 또는 모듈별 `.data`)
- Cache:
  - Redis (선택사항, 미기동 시 `/actuator/health` DOWN 가능)
- Required Env Vars / Profiles:
  - `SPRING_PROFILES_ACTIVE=local`
  - 필요 시 `app.market.provider.active`, `app.market.provider.allow-mock`

### Server / Deployment (운영 기준)

- Host:
  - `192.168.30.39` (Rocky Linux)
- Service names (systemd):
  - `gnd-h2`
  - `gnd-api`
  - `gnd-web`
  - `gnd-batch`
- Deploy path:
  - `/home/was/gnd-news`
- DB path / external dependency:
  - H2 DB file: `/home/was/gnd-news/.data/gnd`
  - H2 TCP: `127.0.0.1:9092` (앱은 TCP로 접속하도록 전환됨)

---

## 7) Frequently Used Commands

### Build / Test

```powershell
./gradlew.bat :core:compileJava :api:compileJava :batch:compileJava -x test --no-daemon
./gradlew.bat :api:test --no-daemon
./gradlew.bat :api:test --tests com.wangbyul.gnd.api.service.signal.ScalpNewsSignalServiceTest --no-daemon
```

### Run (Local)

```powershell
# API (local profile, 필요 시 포트 override)
./gradlew.bat :api:bootRun --args="--spring.profiles.active=local --server.port=18080"

# WEB
./gradlew.bat :web:bootRun --args="--spring.profiles.active=local --server.port=18081"

# BATCH
./gradlew.bat :batch:bootRun --args="--spring.profiles.active=local"
```

### Diagnostics / Health

```powershell
Invoke-WebRequest http://127.0.0.1:18080/actuator/health
Invoke-RestMethod http://127.0.0.1:18080/api/admin/diagnostics/market-collection/summary -Headers @{ "X-API-KEY" = "change-me" }
```

### Manual Market Collection (테스트용)

```powershell
Invoke-RestMethod -Method POST `
  -Uri "http://127.0.0.1:18080/api/admin/market-data/collect" `
  -Headers @{ "X-API-KEY" = "change-me" } `
  -ContentType "application/json" `
  -Body '{"jobType":"QUOTE","provider":"mock","triggeredBy":"manual-test"}'
```

### DB Quick Checks (H2)

```sql
select count(*) from news;
select provider_name, count(*) from market_quote_snapshot group by provider_name;
select status, count(*) from market_provider_job group by status;
select thumbnail_status, thumbnail_source, count(*) from news group by thumbnail_status, thumbnail_source;
```

---

## 8) Current Configuration Snapshot (Important)

운영 판단에 중요한 값만 기록합니다. (로컬/운영 차이 주의)

- Active provider (local typical): `MOCK`
- `allowMock`:
  - 로컬 기본: `true`
  - 운영 기본 설계: `false`
- `LIVE_TRADE`:
  - 기본 `false`
- Feature toggles (effective, 최근 기준):
  - `LIVE_TRADE=false`
  - `AUTO_ORDER_WITH_ADMIN_APPROVAL=false`
  - `AUTO_ORDER_FULLY_AUTOMATED=false`
  - `RAG_ASSISTANT=true`
- Profiles commonly used:
  - `local`
- Known local-only differences:
  - Redis 미기동 상태가 많아 `/actuator/health`가 `DOWN`
  - MOCK provider 중심으로 수치/등락률이 실데이터와 다름

---

## 9) Completed Work Summary (Recent)

최근 완료 차수 요약입니다. 상세는 `work_timeline.md` 참조.

- [Rebuild Phase 1]
  - Summary: 배포 오류 진단 체계/메타 표준화 + mock/실데이터 구분 강화
  - Key files: `AdminDiagnosticsService`, `InsightController`, `MarketProviderProperties`, `docs/DeploymentVerificationChecklist.md`
  - Commit: `a425923`

- [Rebuild Phase 2]
  - Summary: 시장데이터 Provider 추상화/라우팅 강화 + `allowMock=false` 차단 + 수동 수집 엔드포인트
  - Key files: `MarketDataProviderRouter`, `MarketDataCollectionService`, `AdminMarketDataController`, `V13__...`
  - Commit: `51a885a`

- [Rebuild Phase 3]
  - Summary: 뉴스 썸네일 파싱/저장/렌더링 정상화 + 썸네일 진단 API
  - Key files: `NewsThumbnailParser`, `FetchServiceImpl`, `NewsController`, `web/ui.js`, `V14__...`
  - Commit: `e5caddf`

- [Rebuild Phase 4]
  - Summary: 종목 유니버스 재구성 + 핵심분야 반영 + 전략패널 중복 억제 + theme ALL 500 수정
  - Key files: `UniverseRebuildService`, `TradingSignalEngineService`, `InsightController`, `AssetUniverseEntity`, `V15__...`
  - Commit: `b336ca0`

- [Rebuild Phase 5]
  - Summary: 뉴스-종목 매핑/RULE_V1 강화 + 50% 수렴 제거 + 데이터부족 상태값 분리 + 상세 근거 응답 강화
  - Key files: `ScalpNewsSignalService`, `TradingSignalEngineService`, `SignalDetailDto`, `TradingSignalViewDto`
  - Commit: `b44653f`

---

## 10) Open Issues / Known Limitations

### Issue A: `/actuator/health`가 로컬에서 DOWN(503)
- Symptom: 앱은 동작하는데 헬스체크가 `DOWN`
- Reproduction: 로컬에서 Redis 없이 `api` 기동 후 `/actuator/health`
- Current hypothesis: `RedisReactiveHealthIndicator` 실패
- Evidence (API/log/DB):
  - `{"status":"DOWN"}` + Redis connection WARN 로그
- Workaround:
  - Redis 기동 또는 헬스체크 해석 시 “기능 API 정상 여부” 별도 확인
- Owner / Next action:
  - 필요 시 로컬 프로필에서 Redis health 완화 여부 검토

### Issue B: 운영 배포 후 AI 비서에서 `trading_signal` 테이블 없음 가능성
- Symptom: AI 비서 패널 로딩 시 SQL 에러 (`trading_signal not found`)
- Reproduction: 서버 H2 스키마가 코드 대비 뒤처진 상태로 배포
- Current hypothesis: H2 마이그레이션 누락/드리프트
- Evidence:
  - 과거 스크린샷/운영 에러 로그
- Workaround:
  - 서버 H2 스키마 점검 + 마이그레이션 적용 + 서비스 재기동
- Owner / Next action:
  - 다음 운영 배포 전 DB schema smoke-check 자동화 검토

### Issue C: KR 뉴스 썸네일 다수 `EMPTY/DEFAULT`
- Symptom: 카드가 placeholder 이미지로 보임 (실이미지 적음)
- Reproduction: KR Google RSS 기반 기사 조회
- Current hypothesis: 소스 자체에 이미지 메타 부재 (`enclosure/og/twitter/body img` 없음)
- Evidence:
  - 썸네일 진단 API에서 `EMPTY/DEFAULT` 다수
- Workaround:
  - 이미지 메타 풍부한 소스 추가 / 후처리 backfill 검토
- Owner / Next action:
  - 소스별 성공률 진단 및 소스 개선

### Issue D: MOCK provider 수치가 실가격처럼 보일 위험
- Symptom: 관심종목/AI 비서 수치가 실제 시장가격으로 오인될 수 있음
- Reproduction: `provider=MOCK` 상태에서 UI 조회
- Current hypothesis: UI 배지/문구가 약한 화면 존재
- Evidence:
  - 관리자/진단 응답에는 경고 포함, 일부 UI는 강화 필요
- Workaround:
  - 응답 `warnings/provider_name/mock_provider_warning` 기반 UI 배지 표시 강화
- Owner / Next action:
  - 다음 차수에서 UI 경고 강화(소규모)

---

## 11) Pending Work (Next Phase Input)

다음 차수는 사용자가 별도 지시 예정. 아래는 일반적인 준비 상태입니다.

- Goal:
  - 5차 이후 고도화(예: 실 Provider 연동 / UI 상태 배지 강화 / 운영 검증 자동화)
- Scope (In) 예시:
  - 실 Provider(Toss/Kiwoom) 인증/호출/에러 매핑
  - 패널/상세 UI에 `analysis_state`, `mock/degraded` 시각화
  - 운영 배포 전후 smoke-check 자동화
- Out of scope 예시:
  - 실주문 자동화 활성화 (`LIVE_TRADE=true`)
  - 대규모 UI 재디자인
- Acceptance criteria 예시:
  - provider 실패 시 mock 혼합 여부가 명확히 구분되고 운영에서는 차단됨
  - `trace_id`, provider 상태, warnings가 진단 가능
- Required evidence:
  - `/actuator/health`
  - API JSON 샘플 (`trace_id` 포함)
  - DB 분포/감사 로그 요약
  - provider/toggle 상태

수정 후보 파일(다음 차수에서 자주 건드리는 위치):
- `core/src/main/java/com/wangbyul/gnd/core/market/provider/*`
- `core/src/main/java/com/wangbyul/gnd/core/service/MarketDataCollectionService.java`
- `core/src/main/java/com/wangbyul/gnd/core/service/UniverseRebuildService.java`
- `api/src/main/java/com/wangbyul/gnd/api/service/signal/ScalpNewsSignalService.java`
- `api/src/main/java/com/wangbyul/gnd/api/service/signal/TradingSignalEngineService.java`
- `api/src/main/java/com/wangbyul/gnd/api/service/AdminDiagnosticsService.java`
- `web/src/main/resources/static/js/ui.js`
- `web/src/main/resources/static/js/app.js`

---

## 12) Verification Evidence Checklist (Per Phase)

차수 종료 시 아래 증거를 재현 가능하게 제출합니다.

- `/actuator/health` 결과 (status + trace_id + body 요약)
- 서버/앱 로그 WARN/ERROR 요약 (가능 범위)
- 관련 API 응답 샘플 JSON (`trace_id` 포함)
- DB 확인 쿼리 + 결과 요약
- Feature toggle / provider 상태
- (필요 시) 화면 전/후 비교 설명

증거 파일 저장 경로(예시):
- `.run/phaseX_*`
- `docs/evidence/...`

---

## 13) Git / Commit / Push Notes

- Working branch: `feature/ai-stock-assistant-rebuild`
- Last known good commit: `b44653f` (5차 완료 시점)
- Uncommitted changes (현재 로컬에서 자주 남는 것, 커밋 전 확인 필요):
  - `prompt.md` (사용자 지시문 업데이트)
  - 일부 테스트 파일 (예: `BacktestComparisonServiceTest.java`) 로컬 인코딩/실험 변경 가능
  - 산출물 폴더/파일 (`.data`, `.run`, `bin`, `*.zip` 등)
- Ignore / do not commit artifacts:
  - `.data/`
  - `.run/`
  - `*/bin/`
  - `.deploy/`
  - `.tools/` (로컬 도구 캐시/다운로드 성격)
  - 기타 생성물/임시 스크립트

커밋 메시지 규칙(실사용 예시):
- `feat: ...`
- `fix: ...`
- `chore: ...`

---

## 14) New Session Bootstrap Prompt (Copy/Paste)

새 세션에서 컨텍스트를 가볍게 시작할 때 사용.

```text
다음 파일을 먼저 읽고 현재 상태를 요약해줘.
- prompt.md
- work_timeline.md
- PROJECT_HANDOVER.md

작업 브랜치는 feature/ai-stock-assistant-rebuild 이고, main 직접 작업 금지야.
요약 후 대기해.
```

차수 작업 지시 템플릿:

```text
[N차 작업 지시] <작업명>

목표:
- ...

작업 범위:
1) ...
2) ...

반드시 포함:
- trace_id 유지
- mock/provider 상태 진단 가능성 유지

검증/제출:
- /actuator/health 결과
- 로그 WARN/ERROR 요약
- API 응답 샘플(JSON, trace_id 포함)
- DB 확인 결과 요약
- feature toggle / provider 상태
- 커밋/푸시

금지:
- 범위 밖 작업
- 실주문 활성화(LIVE_TRADE=true)
```

---

## 15) Context Optimization Tips (Optional but Useful)

- 긴 공통 규칙은 매번 채팅에 반복 붙여넣지 말고 `AGENTS.md`/`PROJECT_HANDOVER.md` 참조
- 스크린샷은 꼭 필요한 것만 첨부 (중복 첨부 지양)
- 긴 로그는 원문 대신 경로 + 핵심 요약만 전달
- 차수 종료 후:
  - `work_timeline.md` 업데이트
  - `PROJECT_HANDOVER.md` 핵심 상태 갱신
  - 필요 시 새 세션에서 재개
- 산출물 폴더 정리/무시로 `git status` 체감 속도 개선

---

## 16) Handover Changelog (This File)

- 2026-02-23: `PROJECT_HANDOVER_TEMPLATE.md` 기반 프로젝트 전용 인수인계 문서 최초 작성


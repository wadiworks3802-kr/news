# Project Handover Template

이 문서는 "새 세션/새 담당자"가 빠르게 현재 프로젝트 상태를 파악하고 작업을 이어갈 수 있도록 만드는 범용 인수인계 템플릿입니다.

사용 방법:
- 프로젝트 시작 시 이 파일을 복사해 `PROJECT_HANDOVER.md` 같은 실제 인수인계 파일로 사용
- 차수/릴리즈 단위로 핵심 상태만 갱신
- 긴 로그/스크린샷은 여기 붙이지 말고 경로/요약만 기록

---

## 1) Project Metadata

- Project Name:
- Repository URL:
- Default Branch:
- Working Branch:
- Current Environment (Local/Dev/Staging/Prod):
- Last Updated (YYYY-MM-DD HH:mm, TZ):
- Maintainer / Owner:

---

## 2) Reference Documents (Must Read First)

- `prompt.md` (요구사항/정책/차수 기준)
- `work_timeline.md` (변경 이력)
- Additional Specs / RFCs:
  - `...`
- 운영/배포 문서:
  - `...`

읽기 순서(권장):
1. `prompt.md`
2. `work_timeline.md`
3. 이 문서 (`PROJECT_HANDOVER.md`)
4. 현재 차수 관련 코드/문서

---

## 3) Current Status Summary (One Screen)

- Current Phase / Sprint:
- What works now:
  - ...
- What is partially implemented:
  - ...
- Known broken / blocked items:
  - ...
- Highest risk area right now:
  - ...

---

## 4) Working Rules / Guardrails

필수 규칙만 짧게 적습니다. (긴 규칙 전문은 `prompt.md`/`AGENTS.md`로 관리)

- Scope rule: 지정된 차수/범위 밖 작업 금지
- Git rule: `main` 직접 작업 금지, 기능 단위 `commit + push`
- Data rule: DB schema 변경 시 `COMMENT` 필수
- Safety rule: 운영 화면에 mock 데이터가 보이면 경고 표시 필수
- Trading rule: `LIVE_TRADE=false` 기본 유지 (명시 승인 전 활성화 금지)
- Trace rule: 모든 분석/수집 결과 `trace_id` 유지
- Reporting rule: 차수 종료 시 검증 증거 제출 (`health`, 로그 요약, API 샘플, DB 요약, toggle/provider 상태)

프로젝트별 추가 규칙:
- ...

---

## 5) Architecture / Module Overview

핵심 모듈과 역할만 적습니다.

- `api`:
- `core`:
- `collector`:
- `batch`:
- `web`:
- `nlp`:
- `infra` / `scripts`:

핵심 데이터 흐름 (요약):
1. ...
2. ...
3. ...

---

## 6) Runtime / Environment Setup

### Local

- Required JDK(s):
- Build Tool:
- DB:
- Cache:
- Required Env Vars / Profiles:
  - `SPRING_PROFILES_ACTIVE=...`
  - `...`

### Server / Deployment (if applicable)

- Host:
- Service names (systemd / process):
  - `...`
- Deploy path:
- DB path / external dependency:
  - `...`

---

## 7) Frequently Used Commands

### Build / Test

```powershell
# Example (replace with project-specific commands)
./gradlew.bat :api:compileJava -x test --no-daemon
./gradlew.bat :api:test --no-daemon
```

### Run (Local)

```powershell
# Example
./gradlew.bat :api:bootRun
```

### Diagnostics / Health

```powershell
# Example
Invoke-WebRequest http://127.0.0.1:8080/actuator/health
```

### DB Quick Checks

```sql
-- Example
select count(*) from some_table;
```

---

## 8) Current Configuration Snapshot (Important)

운영 판단에 중요한 값만 기록합니다.

- Active provider:
- `allowMock`:
- `LIVE_TRADE`:
- Feature toggles (effective):
  - `...`
- Profiles currently used:
- Known local-only differences:
  - ...

---

## 9) Completed Work Summary (Recent)

최근 완료 차수/작업을 짧게 정리합니다. 상세는 `work_timeline.md` 참조.

- [Phase / Task ID]:
  - Summary:
  - Key files:
  - Commit:

- [Phase / Task ID]:
  - Summary:
  - Key files:
  - Commit:

---

## 10) Open Issues / Known Limitations

문제 재현 조건 + 원인 가설 + 임시 대응을 적습니다.

### Issue A
- Symptom:
- Reproduction:
- Current hypothesis:
- Evidence (API/log/DB):
- Workaround:
- Owner / Next action:

### Issue B
- Symptom:
- Reproduction:
- Current hypothesis:
- Evidence:
- Workaround:
- Owner / Next action:

---

## 11) Pending Work (Next Phase Input)

다음 차수에서 할 작업을 "실행 가능한 단위"로 적습니다.

- Goal:
- Scope (In):
  - ...
- Out of scope:
  - ...
- Acceptance criteria:
  - ...
- Required evidence:
  - ...

수정 후보 파일:
- `...`
- `...`

---

## 12) Verification Evidence Checklist (Per Phase)

작업 종료 시 아래 증거를 재현 가능하게 제출합니다.

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

- Working branch:
- Last known good commit:
- Uncommitted changes (intentional?):
  - ...
- Ignore / do not commit artifacts:
  - `.data/`
  - `.run/`
  - `*/bin/`
  - `.deploy/`
  - 기타 생성물

커밋 메시지 규칙(예시):
- `feat: ...`
- `fix: ...`
- `chore: ...`

---

## 14) New Session Bootstrap Prompt (Copy/Paste)

새 세션에서 컨텍스트를 가볍게 시작할 때 사용하는 기본 프롬프트 예시입니다.

```text
다음 파일을 먼저 읽고 현재 상태를 요약해줘.
- prompt.md
- work_timeline.md
- PROJECT_HANDOVER.md

작업 브랜치는 <branch-name> 이고, main 직접 작업 금지야.
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
- ...

검증/제출:
- /actuator/health 결과
- 로그 WARN/ERROR 요약
- API 응답 샘플(JSON, trace_id 포함)
- DB 확인 결과 요약
- 커밋/푸시

금지:
- ...
```

---

## 15) Context Optimization Tips (Optional but Useful)

컨텍스트/속도 문제를 줄이기 위한 운영 팁입니다.

- 긴 공통 규칙은 매번 채팅에 반복 붙여넣지 말고 문서(`AGENTS.md`, `PROJECT_HANDOVER.md`) 참조
- 스크린샷은 꼭 필요한 것만 첨부
- 긴 로그는 원문 대신 경로 + 핵심 요약만 전달
- 차수 종료 후:
  - `work_timeline.md` 업데이트
  - `PROJECT_HANDOVER.md` 핵심 상태 갱신
  - 새 세션으로 재개 (필요 시)
- 산출물 폴더 정리/무시로 `git status` 체감 속도 개선

---

## 16) Handover Changelog (This File)

- YYYY-MM-DD: Initial template created
- YYYY-MM-DD: ...


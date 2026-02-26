# PROMPT-FIX-2026-02-25

목적:
- AI 비서/전략 패널의 대형 오류 노출과 레이아웃 붕괴를 복구한다.
- 스키마 드리프트(특히 `asset_universe.panel_exposure_count_24h`)로 인한 500을 완화한다.
- 뉴스 카드의 HTML 엔티티 노출(`&nbsp;`, `&quot;`)을 사용자 표시 텍스트에서 제거한다.

배경 문제:
1. 전략/비서 패널에서 SQL 예외 원문이 그대로 노출됨.
2. 예외 메시지 길이 + 헤더 flex 조합으로 제목이 세로 한 글자씩 줄바꿈됨.
3. 일부 경로에서 오류 문자열이 이중 escaping 되어 `&quot;`가 그대로 보임.
4. 기존 데이터의 제목/요약에 HTML 엔티티가 남아 카드 가독성 저하.
5. 운영/로컬에서 Flyway 미적용 또는 DB 드리프트 시 신규 컬럼 누락.

작업 지시:
1) 서버 오류 응답 안전화
- `GlobalExceptionHandler`에서 SQL/DB 내부 메시지 원문 직접 반환 금지.
- 스키마 불일치 패턴은 `SCHEMA_MISMATCH` 코드로 표준화.
- 일반 500은 사용자용 일반 메시지로 축약하고 상세는 로그에만 남긴다.

2) 스키마 호환성 자동 보정(경량)
- 앱 기동 시 `asset_universe`의 필수 컬럼(`country_code`, `strategy_scope`, `last_panel_exposed_at`, `panel_exposure_count_24h`) 누락을 점검한다.
- 누락 시 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`로 보정 시도.
- 보정 실패 시 앱은 죽이지 말고 경고 로그 + 이후 API에서 표준 오류(`SCHEMA_MISMATCH`)로 처리.

3) 프론트 오류 렌더/레이아웃 보정
- 헤더 메타 영역에 장문 오류 원문을 직접 넣지 않는다(짧은 문구만 노출).
- 에러 상세는 패널 본문의 `empty-box`에 제한 길이로 표시.
- `.stock-head/.analysis-head` 메타는 `ellipsis` 처리로 제목 폭 붕괴 방지.
- `renderAssistantError`의 이중 escape(`.text` + escapeHtml) 제거.

4) 뉴스 텍스트 가독성 보정
- 카드 렌더 전 텍스트에서 HTML 엔티티를 decode(`&nbsp;`, `&quot;`, `&amp;`, `&lt;`, `&gt;`, `&#39;`) 후 표시.
- 태그 제거/공백 정규화 후 escapeHtml 적용.
- 수집/요약 경로에서도 엔티티 decode를 적용해 신규 적재 데이터 품질 개선.

수용 기준:
- `/api/insight/signals/*`, `/api/insight/assistant/dashboard` 실패 시 SQL 원문 대신 축약된 코드/메시지 노출.
- 패널 제목이 세로로 깨지지 않고 1줄 유지(메타는 생략/말줄임).
- 카드 제목/요약에서 `&nbsp;` 문자열이 그대로 보이지 않음.
- `panel_exposure_count_24h` 누락 DB에서도 기동 직후 자동 보정 시도 로그 확인.

검증:
- 로컬 `:api:compileJava`, `:web:processResources` 실행.
- UI 수동 확인: 뉴스 홈/전략 패널/AI 비서 탭.
- 에러 응답 샘플 확인: `code`, `message`, `trace_id`.

---

# PROMPT-FIX-2026-02-25 (실데이터 운영 전환 / 키움 연동 안정화)

목적:
- 뉴스 수집이 실제 소스 기준으로 1분 단위 정상 적재되도록 복구한다.
- 시장데이터 provider를 Kiwoom 실데이터로 전환하고 mock 경로를 운영에서 차단한다.
- 하드코딩된 종목 시그널 후보를 제거해 DB 기반 동적 후보군으로 대체한다.

작업 지시:
1) 키움 REST provider 실구현
- OAuth2 토큰 발급(`/oauth2/token`) + 토큰 캐시/갱신 구현.
- 현재가 조회(`/api/dostk/stkinfo`, `api-id=ka10099`)를 내부 `MarketQuoteDto`로 정규화.
- 분봉/차트 조회(`/api/dostk/chart`, `api-id=ka10080`)를 내부 `MarketPriceBarDto`로 정규화.
- timeout/예외/응답오류 시 표준 `MarketProviderFetchResult.failure`로 기록.
- appkey/secret은 코드 하드코딩 금지(환경변수 또는 파일 경로 주입).

2) 뉴스 수집 소스 자동 보장(시드와 분리)
- `app.seed.enabled=false` 환경에서도 기본 RSS 소스가 비어 있으면 자동 등록.
- source의 `allow_fetch=true`, `source_grade=P1`, endpoint/priority가 유효하도록 upsert.
- 배치 FetchJob(1분)이 소스 0건으로 무동작 상태가 되지 않게 한다.

3) 하드코딩 시그널 제거
- `StockSignalService`의 국가별 고정 종목 목록 제거.
- `asset_universe(active=true)` + (가능 시) alias/뉴스 매칭 기반 동적 후보 선정으로 변경.
- 데이터 부족 시에도 임의 고정종목 반환 금지(빈 결과/명확한 사유 반환).

4) 운영 프로필/배포 설정 정비
- 운영 프로필에서 `app.market.provider.active=kiwoom`, `allow-mock=false`, `fallback-to-mock-on-failure=false`.
- `LIVE_TRADE=false` 유지, `seed.enabled=false` 유지.
- systemd 배포 스크립트에 Kiwoom key 파일 경로 환경변수 연결.

수용 기준:
- `/api/admin/sources`에 기본 수집 소스가 존재하고 1분 배치에서 실제 수집 건수가 증가한다.
- `market_provider_job`/`market_quote_snapshot`에 `provider_name=kiwoom` 데이터가 적재된다.
- `/api/insight/stocks` 응답이 고정 대표주 하드코딩 없이 동적으로 구성된다.
- 운영 설정에서 mock provider 사용/자동 fallback이 차단된다.

검증:
- `:core:compileJava`, `:api:compileJava`, `:batch:compileJava` 성공.
- 수동 수집 API(`POST /api/admin/market-data/collect`) QUOTE/BAR 성공 또는 명확한 오류코드 확인.
- 진단 API에서 provider/toggle 상태 확인(`kiwoom`, `allow_mock=false`).

---

# PROMPT-FIX-2026-02-25 (키움 키파일 반영 + 1분 수집 + 실운영 배포 고정)

목적:
- `appkey.txt`, `secretkey.txt`를 실제 실행 경로에서 읽어 키움 API가 실동작하도록 고정한다.
- 뉴스/시세 수집을 1분 단위로 지속 수행하도록 보장한다.
- 운영 배포에서 mock provider 경로를 차단하고 실데이터 모드만 허용한다.

핵심 원칙:
1) 키/토큰/종목 코드 하드코딩 금지
- appkey/secretkey는 코드 상수 금지.
- 파일 경로(`KIWOOM_APPKEY_FILE`, `KIWOOM_SECRETKEY_FILE`) 또는 환경변수로만 주입.
- 예외 메시지에 key/token 원문 포함 금지.

2) 스케줄 수집 1분 보장
- 뉴스 fetch(P1), 시세 quote, 분봉 bar를 모두 1분 간격 유지.
- 소스가 0건이면 source-bootstrap이 자동 보정되도록 유지.
- 수집 실패 시 스케줄 자체가 멈추지 않도록 장애 격리(실패 기록 + 다음 주기 재시도).

3) 운영 프로필 강제
- `SPRING_PROFILES_ACTIVE=ops`.
- `app.market.provider.active=kiwoom`.
- `allow-mock=false`, `fallback-to-mock-on-failure=false`.
- `app.seed.enabled=false`.
- `app.trade.live-enabled=false` (실주문 OFF 유지).

작업 지시:
1) 설정/스크립트 점검
- `api/batch application-ops.yml`의 provider/seed/toggle 값이 위 원칙과 일치하는지 점검.
- systemd 설치 스크립트에 키 파일 경로 환경변수와 ops profile이 확실히 주입되는지 점검.
- H2/DB 계정값 충돌로 기동 실패하지 않도록 datasource env 일관성 확인.

2) 키움 provider 런타임 검증 가능화
- 키 파일 누락/읽기 실패 시 `KIWOOM_CREDENTIAL_MISSING` 등 표준 코드로 진단 가능해야 함.
- provider-audit/health에서 성공/실패 원인이 추적 가능해야 함(trace_id 포함).

3) 하드코딩 제거 검증
- 개인용 주식 시그널/전략 후보가 고정 종목 목록이 아닌 `asset_universe + news/alias` 기반으로 계산되는지 확인.
- 데이터 부족 시 “고정 대표주 fallback” 금지(빈 결과 + 사유 반환).

4) 배포 및 검증
- 대상: `192.168.30.39` (`was/was`, 관리자 명령 시 `sudo`).
- 빌드 후 systemd 재설치/재기동.
- 필수 증거:
  - `/actuator/health` (api, batch/web 상태)
  - `/api/admin/sources` (source > 0, allow_fetch=true)
  - provider 진단(kiwoom, allow_mock=false)
  - DB 집계(최근 10분 news/quote/bar 증가 여부)
  - 서버 로그 ERROR/WARN 요약(키/토큰/연결 실패 여부)

수용 기준:
- 운영 서버에서 mock 비활성 상태로 API/BATCH 기동 성공.
- 1분 내 뉴스/시세 적재 건수 증가 확인.
- UI/진단에서 provider가 `KIWOOM`으로 보고되고 mock 경고가 꺼져 있음.
- 고정 대형주만 반복 노출되는 정적 fallback이 제거되어 동적 결과가 출력됨.

---

# PROMPT-FIX-2026-02-25 (전략패널 동시 오류 해소 + AI 비서 전략 분리 가독화)

목적:
- 전략 분석 패널 4개가 동시에 `데이터 조회 중 오류...`로 무너지는 현상을 안정화한다.
- AI 비서에서 단타/중기/차트/발굴이 동일 종목으로 수렴하는 현상을 완화한다.
- 기능 로직 대수술보다 화면 가독성과 전략별 이해도를 우선 개선한다.

원인 가설(우선순위):
1) 전략 패널 로더가 병렬 요청 중 1개라도 실패하면 전체 패널을 오류로 덮어씀.
2) `asset_universe` 쓰기 경합(특히 panel exposure update, 신호 생성 시점)으로 H2 lock timeout(HYT00) 발생.
3) AI 비서 전략 카드가 전략별 후보 다양성 제어 없이 동일 종목에 수렴.

작업 지시:
1) 프론트 로더 안정화
- `loadAdvancedPanels`를 `Promise.allSettled` 기반으로 전환.
- 패널별 부분 실패 허용(성공 패널은 그대로 렌더, 실패 패널만 오류 박스 표시).
- 상단 메타에 로드 성공/실패 개수 표시.

2) 백엔드 락 경합 완화
- 패널 조회 경로에서 불필요한 `asset_universe` 엔티티 변경/쓰기 최소화.
- panel exposure 업데이트는 토글(`app.universe.panel-exposure-write-enabled`)로 제어하고 기본 OFF.
- 최근 신호 보장 로직(`ensureRecentSignals`)은 윈도우별 조회 정확도를 높이고, 생성 실패 시 전체 API 실패로 전파하지 않도록 완화.

3) AI 비서 전략 분리 가독화
- 전략별 후보를 교차 중복 최소화(가능하면 unique asset 우선 배치).
- 단타/중기/차트/발굴 각 행에 전략근거(핵심지표/상태/요약)를 명확히 노출.
- 전략 다양성 요약(`total_rows`, `unique_asset_count`, `overlap_reused_count`)을 응답에 포함.

4) 디자인 보강(기능 유지)
- 전략 분석 카드에 시각적 계층(카드 톤, 액션 색상, 전략별 좌측 강조선, 요약행) 추가.
- 에러 박스는 패널 내부 단문으로 제한(헤더 레이아웃 붕괴 방지).

수용 기준:
- 패널 API 일부 실패 상황에서도 4개 패널 전체가 동시에 오류로 덮이지 않는다.
- `asset_universe` lock timeout이 발생해도 전략 패널 API가 연쇄 500으로 붕괴하지 않는다.
- AI 비서 전략 탭에서 동일 종목 반복 비율이 완화되고 전략별 근거 문구가 구분되어 보인다.
- 기존 주문/토글/리스크 계산 기능 회귀 없음.

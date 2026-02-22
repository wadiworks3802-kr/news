PROMPT-0

목적: 공식 소스 기반 글로벌 뉴스 대시보드(ko 번역/요약/검증/배포)를 구현하라.

입력:

\[가정] 대상국=KR,US,UK,DE,FR,JP,CN,IN,RU,BR; 스택=Java25+SpringBoot3.x+Spring Security+MVC+JPA+Postgres+Redis+Quartz+Docker+Nginx+jQuery4.0+Cloud+OTel+LLM

\[미지정] 번역/요약 공급자, 분류모델/COMET/SummaC 산출기, FactCheck(ClaimReview) 조회원, 시세/브로커 API, 단가(k1~k4), 알림 채널

입력사양: 소스등급 P0(공식RSS/API) P1(언론RSS) P2(집계/GDELT) P3(YouTube메타)

필수필드: id,country,lang,url,title\_raw,body\_raw,pub\_utc(RFC3339),fetch\_utc,license,robots,ttl

빈도: P0 5m,P1 15m,P2/P3 60m

작업지시(단계/임계값 고정):

수집: condGET(ETag/If-Modified-Since), timeout(connect 3s/read 10s), HTTP429→retry3(지수백오프 1/4/16m)

정합성: pub\_utc 우선, 없으면 fetch\_utc+flag

중복: URL정규화(RFC3986)→hash(SHA-256 title+body+pub)→SimHash/MinHash 군집; DEDUP simhash\_threshold=0.85(대표1 노출)

번역: 원문보존; TR: MQM\_critical=0 \& COMET>=0.80(수치/단위/부정반전 발견 시 FAIL)

요약: BRK=2문장/일반=4~6문장+fact\_box(when/who/what/impact); SUM: SummaC>=0.75 \& evidence\_spans 필수

분류: 룰→모델→다중태깅≤5; conflict(공식발표→POL, 지표→ECO)

추천: REC: G1..G5(출처≥2/수치근거/개인화금지/리스크표기/지시문금지), 하나라도 미충족→생성금지

검증: ClaimReview 연동+fact-check 조회+교차출처 수/공식 여부로 trust\_score 산정

저장: raw(불변)/normalized/derived 분리, TTL/robots/license 강제, P1~P3 raw 저장금지 기본값

배포: API+웹/모바일, 신뢰도 배지·출처·근거스팬 노출, P0 BRK 카드 SLA 2분

산출물: 다음 PROMPT에서 Exec summary, mermaid(패키지/DB/ER), API 스펙 표, 보안·운영 체크리스트, 테스트케이스 표(20+), 자기검증 체크리스트를 생성하라.

검증 체크리스트: \[ ]필드/빈도/임계 누락0 \[ ]정책위반0 \[ ]SLA2분 준수



PROMPT-1

목적: Spring MVC 멀티모듈 구조, DB 스키마/인덱스/ER, 기본 수집·전처리·저장 골격 코드를 생성하라.

입력: PROMPT-0 기준.

작업지시:

멀티모듈: core/collector/nlp/api/web/batch (Gradle 멀티모듈)

base package: com.wangbyul.gnd

핵심 클래스(목록을 표로 산출):

Entity: NewsEntity, SourceEntity, FetchJobEntity, InsightLogEntity

Repo: NewsRepository, SourceRepository, FetchJobRepository, InsightLogRepository

Service: FetchService, NormalizeService, DedupService, NlpPipelineService

Controller: NewsController, InsightController, AdminController

Security: SecurityConfig

Quartz: FetchJob, NlpJob, CleanupJob

Observability: OtelFilter(HTTP/Batch trace\_id 부여)

DB 스키마(테이블·컬럼·인덱스 “상세 목록”을 문서로 산출 + SQL migration 생성):

news: (입력필드 11개)+url\_norm,title\_ko,summary\_ko,content\_hash,simhash64,dedup\_group\_id,trust\_score,created\_at

source: sid(PK),country,priority,allow\_fetch,allow\_store\_raw,allow\_store\_derived,cache\_ttl\_seconds,license\_policy,robots\_policy

fetch\_job: id(PK),sid(FK),status,attempt,last\_error,scheduled\_at,updated\_at

insight\_log: id(PK),news\_id(FK),category,generated\_at,content,risk\_flag,trace\_id

인덱스 필수: uq(news.url\_norm), idx(category,country,pub\_utc desc), idx(content\_hash), idx(simhash64), gin(title\_ko), gin(summary\_ko), idx(created\_at)

ER 핵심관계: source 1-N news, source 1-N fetch\_job, news 1-N insight\_log

산출물: DB/ER 다이어그램(mermaid), 스키마 SQL, JPA 엔티티/리포지토리, 배치 잡 골격, 정책 엔진(robots/license/ttl) 골격.

검증 체크리스트: \[ ]모듈/클래스 표 포함 \[ ]테이블/인덱스/ER 포함 \[ ]P1~P3 raw 저장금지 기본값 반영

다이어그램:

erDiagram

&nbsp; SOURCE ||--o{ NEWS : provides

&nbsp; SOURCE ||--o{ FETCH\_JOB : schedules

&nbsp; NEWS ||--o{ INSIGHT\_LOG : logs



PROMPT-2

목적: API 계약(OpenAPI), 입력 유효성, 응답 스키마, jQuery4 SPA 화면을 생성하라.

입력: PROMPT-0~1 기준.

작업지시:

API 엔드포인트(표로 산출 + Controller 구현):

GET /api/news?country\&category\&sort\&period\&page\&q

GET /api/news/{id}

GET /api/insight?category\&period\&country

CRUD /api/admin/sources (ADMIN 전용)

유효성(강제): country=^\[A-Z]{2,5}$, category∈{BRK,POL,ECO,MKT,DEV,IND}, url=^https?://, null 금지, RFC3339 파싱 실패→400, timeout=connect3/read10, upstream 예외→표준 오류 응답

응답 규격(강제): 성공={data,meta,trace\_id}, 오류=ErrorDto(code,message,trace\_id)

Front(jQuery4.0, 모바일 우선): 국가/카테고리/정렬/기간/검색 필터, 스켈레톤+재시도, 카드(제목2줄/요약4~6줄), 출처/신뢰도/근거스팬 버튼

산출물: API 스펙 표(OpenAPI 3), 요청/응답 JSON 예시, web 정적파일(app.js/api.js/store.js/router.js/ui.js), 캐시 키 설계(뉴스 목록 Redis 캐시).

검증 체크리스트: \[ ]정규식/NULL/RFC3339/timeout 반영 \[ ]ErrorDto 통일 \[ ]모바일 1열 렌더링

EP	요청/검증 요약

GET /api/news	country/category 검증, pagination, timeout

GET /api/news/{id}	404 표준화

GET /api/insight	REC 게이트 미충족=추천 생성 금지

/api/admin/sources	ADMIN 권한

ex) 
{

&nbsp; "req": {"country":"KR","category":"ECO","page":1},

&nbsp; "res": {"data":\[{"id":"","country":"KR","category":\["ECO"],"title\_ko":"","summary\_ko":"","pub\_utc":"","trust\_score":0.0,"evidence\_spans":\[]}],"meta":{"page":1},"trace\_id":""},

&nbsp; "err": {"code":"","message":"","trace\_id":""}

}



PROMPT-3

목적: Spring Security/비밀관리/예외·재시도/DLQ/인간검수/관측·롤백/보안·접근성/테스트·QA 및 최종 산출물 패키징을 완성하라.

입력: PROMPT-0~2 기준.

작업지시:

Spring Security(지침+코드): JWT 또는 APIKey, 세션 STATELESS, /api/admin/\*\*=ROLE\_ADMIN, CSRF(/api off), CORS allowlist, 토큰/키 마스킹·회전, 비밀관리=HSM 또는 시크릿볼트 권장(키 저장 최소화)

예외/재시도/DLQ/인간검수: retry3+backoff(1/4/16m), 최종 실패 DLQ, 인간검수 트리거=파서실패/언어미검출/TR·SUM 미달/중복폭주/disputed/robots=false/ttl만료, 우선순위=P0>P1>P2/P3

모니터링/로깅/롤백: metrics={수집\_rate,latency,dup\_rate,translation\_fail\_rate,summary\_factuality,trust\_score\_dist,cost\_per\_item}, trace\_id 전파(W3C traceparent), 롤백단위={connector,ruleset,prompt,model,schema}

OTel 계측: Servlet Filter+WebClient Interceptor+Quartz Job span, 배치/HTTP 동일 trace\_id, JSON 로그

보안/접근성/호환성 체크: OWASP ASVS L2, WCAG 2.1 AA, Chrome/Edge/Safari 최신-2, 모바일 터치 44px

테스트(20개 이상 “표로 예시” 포함): 단위/통합/부하/보안(예: RFC3339, condGET, 429 retry, URL정규화, uq중복차단, SimHash0.85군집, TR/SUM 게이트 fail/pass, evidence\_spans 필수, REC 게이트, ClaimReview, TTL purge, DLQ, CORS/CSRF/authz, 부하 p95<1s 등)

QA 합격 기준(강제): 치명적 오류 0, 정책 위반 0, P0 BRK SLA 2분

산출물(최종 1회 출력): Executive summary, 패키지/DB/ER mermaid, API 스펙 표, 보안·운영 체크리스트, 테스트케이스 표(20+), 최종 자기검증 체크리스트, 전체 코드(Java/SQL/JS).

검증 체크리스트: \[ ]치명0 \[ ]정책0 \[ ]SLA2분 \[ ]p95<1s \[ ]OTel trace\_id 전구간



PROMPT-4 (2026-02-20 업데이트)

목적: 홈 화면은 "실시간 수집 뉴스 피드"만 노출하고, 요약/핫픽스는 별도 영역(또는 별도 라우트)으로 분리한다.

입력: PROMPT-0~3 + 로컬 운영 개선안(파일기반 H2 공유, 관리자 수동수집, 포털형 섹션 UI).

작업지시:

데이터/수집

1. 로컬 DB는 `jdbc:h2:file:./.data/gnd`를 API/BATCH 공통으로 사용한다.
2. 로컬 시드 정책은 "소스 부트스트랩"과 "샘플 기사 생성"을 분리한다.
3. 기본값은 샘플 기사 비활성(`app.seed.sample-news-enabled=false`)로 두고, 필요 시에만 샘플을 생성한다.
4. `POST /api/admin/ingest/run`은 수동 실시간 수집 트리거로 사용하며, 로컬 fallback 샘플 기사 자동생성은 기본 비활성으로 유지한다.
5. 수집기는 RSS/Atom 본문을 기사 단위로 파싱해 실제 제목/링크/발행시각 기반으로 저장한다.

표출/UI

1. 홈(`index`)은 포털형 섹션 카드(BRK/POL/ECO/MKT/DEV/IND)로 구성하되, 역할은 "실시간 뉴스 피드"에 한정한다.
2. 홈 카드에는 출처/신뢰도/발행시각/근거 버튼만 표준 노출한다.
3. 빈 상태 문구는 "데이터 수집 전, 관리자 수집 실행 필요"를 명확히 표시한다.
4. 요약/핫픽스는 홈과 분리된 별도 기능(추후 `/summary`, `/hotfix` 등)로 설계한다.

운영 절차(로컬)

1. API 기동(`local`) -> Web 기동.
2. `CRUD /api/admin/sources`로 실시간 수집 소스를 등록/점검.
3. `POST /api/admin/ingest/run` 실행으로 뉴스 적재.
4. 홈 화면에서 실시간 기사 카드 확인.

수용 기준:

1. 홈에서 로컬 샘플 문구 기사 대신 실시간 수집 기사가 우선 노출된다.
2. 수동 수집 API 호출 후 `news_created`가 실제 수집 결과 기반으로 증가한다.
3. 홈 화면은 뉴스 나열 중심이며 요약/핫픽스는 분리된 기능 경계로 유지된다.
4. 소스 미등록 또는 수집 전 상태에서 사용자 안내 문구가 명확히 표시된다.


PROMPT-5 (2026-02-20 심화 업데이트)

목적: 국가 전환 시 응답 경합/번역 지연을 줄이고, 홈 화면 가독성/실시간성/개인용 시그널 기능을 강화한다.

입력: PROMPT-0~4 + 실사용 피드백(번역 대기, stale 응답, 썸네일 식별성, 자동 갱신, 종목 시그널 요구).

작업지시:

번역/성능

1. 요청 경로에서 동기 번역을 제거하고 비동기 큐(`NewsLocalizationService`)로 전환한다.
2. 목록 조회 시 번역 미완료 데이터는 원문 fallback으로 즉시 반환하고, `translation_pending` 메타로 상태를 노출한다.
3. 최근 뉴스를 주기적으로 번역 큐에 선적재(`TranslationPrefetchScheduler`)해 첫 조회 지연을 완화한다.
4. 다중 사용자/다중 PC에서도 재조회가 빨라지도록 번역 결과를 DB(`title_ko`,`summary_ko`)에 영속 저장한다.

조회/정렬/캐시

1. 뉴스 정렬은 최신 우선으로 고정(`pub_utc DESC -> fetch_utc DESC -> created_at DESC`).
2. 목록 캐시 TTL을 설정값으로 제어(`app.cache.news-list-ttl-seconds`, 기본 30초)한다.
3. 캐시/비캐시 응답 모두 동일한 `meta.total`/`meta.translation_pending` 구조를 유지한다.

UI/실시간 갱신

1. 프론트는 요청 경합 시 이전 요청을 abort하고 최신 요청만 반영한다(`AbortController` + request sequence guard).
2. 홈 화면은 30초 주기 비동기 자동 갱신을 기본 활성화한다(설정 가능).
3. 실시간 상태 배지(`live-state`)로 자동 갱신 상태/번역 처리 상태를 표시한다.

썸네일/가독성

1. 정적 "LIVE NEWS" fallback 이미지를 제거한다.
2. 원본 썸네일 실패 시 제목/카테고리/출처를 포함한 텍스트 기반 SVG 썸네일로 대체한다.
3. 카드 상단에 카테고리 칩/출처 아이콘을 고정 배치해 한눈 식별성을 높인다.

개인용 종목 시그널

1. `GET /api/insight/stocks?country&period&limit` 엔드포인트를 추가한다.
2. 국가별 대표 종목 후보 + 뉴스 키워드/감성 휴리스틱으로 상승/하락 확률을 계산한다.
3. 응답은 `{stock_code,stock_name,up_probability,down_probability,confidence,matched_articles,reason}` 구조를 사용한다.
4. 홈 화면 상단에 "개인용 주식 시그널" 패널을 추가해 국가 기준 결과를 함께 표출한다.
5. 해당 기능은 투자 자문이 아닌 개인 참고용임을 명시한다.

수용 기준:

1. 국가를 빠르게 연속 변경해도 이전 요청 결과가 늦게 덮어쓰는 현상(stale render)이 발생하지 않는다.
2. 번역 미완료 상태에서도 목록 응답은 즉시 반환되고 화면이 멈추지 않는다.
3. 동일 기사 재조회(다른 사용자 포함) 시 번역 완료 데이터는 즉시 표시된다.
4. 카드 썸네일이 정적 문구 대신 기사 식별 가능한 시각 정보(원본 또는 텍스트 썸네일)로 노출된다.
5. 홈 화면이 자동 갱신되며 최신 기사/시그널이 비동기로 갱신된다.

# PROMPT-6-ADV (전략정책 통합 강화판) — 뉴스+시장데이터+차트 기반 자동매매 준비 아키텍처/구현

## 목적
기존 글로벌 뉴스 대시보드(국가/카테고리별 실시간 뉴스 수집/분류/번역/캐시/UI)를 유지한 상태에서,  
시장데이터(자원/섹터/관심종목) 수집과 뉴스-차트 결합 분석을 추가하고,  
다음 4가지 목적을 동시에 지원하는 **자동매매 준비 시스템(시그널/백테스트/모의매매 중심)** 을 구현하라.

### 분석/매매 목적 (반드시 시스템 설계에 반영)
1. **최근 뉴스 검색 (단타, 매매)**
2. **시장 동향 분석 (중기, 일 매매)**
3. **차트 분석 (장기 전 대응 전략, 평단가 낮추는 형태 포함)**
4. **6개월 후 뜰만한 주식 발굴**

---

## 절대 원칙 (강제)
1. 기존 뉴스 시스템 안정성을 깨지 않는다.
2. 자동매매는 **백테스트/모의매매 완료 전까지 실주문 금지**.
3. 시그널은 반드시 **뉴스 + 차트(가격/거래량/추세)** 결합으로 계산한다.
4. 한 종목 몰빵 금지 (분산투자 규칙 강제).
5. 모든 시그널/주문은 근거(reason_json)와 리스크 판정 로그를 남긴다.
6. UI에 투자자문 문구를 넣지 말고, “참고용 시그널”로 고정 표시한다.

---

## 핵심 전략 정책 (반드시 구현)

### 1) 분석 계층 분리 (4계층)
시스템 내부에 분석 목적별 엔진을 분리 구현하라.

#### A. 단타 뉴스 엔진 (최근 뉴스 검색)
- 목적: 단기 이슈 급등락 대응
- 분석 범위: 최근 10분 / 30분 / 1시간 / 1일
- 입력:
  - 속보/경제/기술 뉴스
  - 자산 직접언급/테마매칭 결과
  - 최근 가격 변화율/체결량/스프레드
- 출력:
  - `scalp_signal_score`
  - `good_news_probability`, `bad_news_probability`
  - `short_term_action` (WATCH / BUY_CANDIDATE / SELL_CANDIDATE / HOLD)

#### B. 시장 동향 엔진 (중기/일 매매)
- 목적: 일중~수일 단위 방향성 판단
- 분석 범위: 1일 / 3일 / 1주
- 입력:
  - 섹터별 뉴스 수/감성/신뢰도
  - 테마별 가격 흐름
  - 지수/섹터 대표 ETF/대표주 흐름
- 출력:
  - `market_regime` (RISK_ON / RISK_OFF / MIXED)
  - `theme_strength_score`
  - `swing_signal_score`

#### C. 차트 대응 엔진 (장기 전 대응 전략 + 평단가 관리)
- 목적: 기존 보유 종목 대응 및 평단가 낮추기 전략 판단
- 분석 범위: 1주 / 1개월 / 3개월 / 6개월
- 입력:
  - OHLCV 시계열
  - 이동평균(5/20/60/120)
  - 거래량 추세
  - 변동성
  - 뉴스 흐름(최근 1주)
- 출력:
  - `position_management_signal`
  - `avg_down_allowed` (bool)
  - `avg_down_stage` (0,1,2,3...)
  - `risk_warning`

#### D. 6개월 발굴 엔진 (중장기 후보 발굴)
- 목적: 6개월 후 상승 가능성이 있는 후보군 탐색
- 분석 범위: 최근 3~6개월 뉴스/가격/테마 축적 데이터
- 입력:
  - 테마 누적 뉴스 모멘텀
  - 기업/종목 언급 증가율
  - 가격 바닥권/추세전환 초기 패턴
  - 거래량 구조 변화
- 출력:
  - `discovery_score`
  - `candidate_rank`
  - `candidate_reason_json`

---

## 매매 방식 정책 (뉴스 + 차트 결합, 강제 반영)

### 2) 기본 매매 방식
- **차트 방식 + 뉴스** 결합으로만 시그널을 생성한다.
- 뉴스만으로 주문하지 않는다.
- 차트만으로 주문하지 않는다.
- “발굴된 주식”은 반드시 **해당 종목/테마 뉴스 발생 여부**를 재확인한 후 시그널을 확정한다.

### 3) 확률 기반 판정값 (반드시 저장)
각 종목/자산별로 아래 값을 계산하여 저장하라.
- `good_news_probability` (호재 확률)
- `bad_news_probability` (악재 확률)
- `news_confidence`
- `chart_confidence`
- `combined_confidence`

규칙:
- `combined_confidence = f(news_confidence, chart_confidence, data_quality, liquidity)`
- 확률값은 0~1 범위
- 근거는 `reason_json`에 키-값 형태로 저장

### 4) 1주일 뉴스 + 차트 동향 분석 (반드시 구현)
주문 전 검증 규칙에 아래 항목을 포함하라.
- 최근 **1주일 뉴스 흐름**
  - 뉴스 수 변화
  - 긍/부정 비중
  - 신뢰도 평균
  - 이슈 연속성
- 최근 **1주일 차트 동향**
  - 고점/저점 갱신 여부
  - 거래량 증가/감소
  - 추세 유지/이탈
  - 변동성 확대/축소

이 결과를 합쳐 `weekly_context_score`를 계산하고 시그널 계산식에 반영하라.

---

## 거래량/매수·매도 연속성 판정 규칙 (사용자 규칙 반영, 강제)

### 5) 연속 매도 발생 규칙
아래 현상을 별도 탐지하라.
- “일정 구간에서 계속적인 매도 발생”

단, 다음 조건이면 **악재로 단정 금지**
- **거래량이 동일 수준이면 악재로 판단하지 않는다**

구현 규칙:
- `continuous_sell_pressure` 탐지
- `volume_regime_same = true` 이면 악재 점수 가중치 축소 또는 무효화
- 결과 필드:
  - `sell_pressure_detected`
  - `sell_pressure_is_negative` (최종 판정)
  - `volume_regime_same`

### 6) 연속 매수 발생 규칙
아래 현상을 별도 탐지하라.
- “일정 구간에서 계속적인 매수 발생”

단, 다음 조건이면 **호재로 단정 금지**
- **거래량이 동일 수준이면 호재로 판단하지 않는다**

구현 규칙:
- `continuous_buy_pressure` 탐지
- `volume_regime_same = true` 이면 호재 점수 가중치 축소 또는 무효화
- 결과 필드:
  - `buy_pressure_detected`
  - `buy_pressure_is_positive` (최종 판정)
  - `volume_regime_same`

### 7) 거래량 동일 판단 기준 (설정값 외부화)
“거래량 동일시”는 감이 아니라 수치로 판정한다.
설정값 예시:
- `volume_same_tolerance_pct` (예: ±5%)
- `volume_compare_window` (예: 최근 20 bar 평균 대비)
- `pressure_detection_window` (예: 최근 10/20/30 bar)

반드시 설정 파일로 외부화:
- `app.signal.volume-same-tolerance-pct`
- `app.signal.pressure-window-bars`
- `app.signal.volume-window-bars`

---

## 자금관리 / 분산 / 익절손절 정책 (핵심, 강제)

### 8) 몰빵 금지 규칙 (필수)
한 종목에 몰빵하지 않도록 리스크 매니저에서 강제 차단하라.

필수 설정값:
- `max_position_ratio_per_asset` (예: 총 투자금 대비 종목당 최대 비중)
- `max_theme_exposure_ratio` (테마별 최대 비중)
- `max_country_exposure_ratio` (국가별 최대 비중)
- `max_open_positions` (동시 보유 종목 수)

주문 전 검증:
- 위 규칙 위반 시 주문 거부 + 사유 기록

### 9) 목표 달성 금액/수익률 기반 익절·손절 정책
사용자 입력 투자금(예: 100만원) 기준으로 매수/매도 비중과 익절/손절 정책을 설정 가능한 구조를 구현하라.

필수 설정/입력값:
- `capital_total` (총 투자금)
- `buy_split_rules` (분할매수 비중 배열, 예: [30, 30, 40])
- `sell_split_rules` (분할매도 비중 배열, 예: [30, 30, 40])
- `take_profit_pct` (익절 기준 %)
- `stop_loss_pct` (손절 기준 %)
- `reanalysis_lock_after_tp_sl` (익절/손절 후 재분석 전 매수 금지 여부)
- `reanalysis_lock_minutes` (재분석 금지 시간)

필수 동작:
- “투자금의 00% 익절 및 손절되었을 때 재분석을 위해 매수 금지” 규칙 구현
- 익절/손절 발생 시 즉시 재진입 금지 상태(`BUY_LOCK`)로 전환
- 잠금 해제 조건:
  - 시간 경과 + 재분석 완료 + 시그널 재생성

### 10) 평단가 낮추기(분할매수) 정책 — 차트+뉴스 동시 조건
“평단가 낮추기”는 무조건 허용하지 말고 아래 조건 충족 시에만 허용한다.

허용 조건(모두 만족):
1. 장기 추세 완전 붕괴 상태 아님 (`trend_breakdown_severe=false`)
2. 최근 1주 뉴스가 치명적 악재 우세 아님 (`bad_news_probability` 과도하지 않음)
3. 유동성 충분
4. 분할매수 단계 한도 미초과
5. 전체 포트폴리오 리스크 한도 미초과

출력 필드:
- `avg_down_allowed`
- `avg_down_reason`
- `avg_down_next_buy_ratio`
- `avg_down_stage`

---

## 시스템 구현 범위 (이번 작업)

### A. DB/도메인 확장 (필수)
기존 뉴스 도메인과 분리하여 아래 테이블/필드를 추가하라.
(기존 prompt 구조 유지 + 전략 정책 필드 추가)

#### 필수 테이블
- `asset_universe`
- `market_price_bar`
- `market_quote_snapshot`
- `news_asset_link`
- `trading_signal`
- `strategy_run`
- `paper_trade_order`
- `paper_trade_position`
- `market_provider_job`

#### `trading_signal` 필드 추가/확장 (전략정책 반영)
- `good_news_probability`
- `bad_news_probability`
- `news_confidence`
- `chart_confidence`
- `combined_confidence`
- `weekly_context_score`
- `scalp_signal_score`
- `swing_signal_score`
- `position_management_signal`
- `discovery_score`
- `sell_pressure_detected`
- `sell_pressure_is_negative`
- `buy_pressure_detected`
- `buy_pressure_is_positive`
- `volume_regime_same`
- `avg_down_allowed`
- `avg_down_stage`
- `reanalysis_lock_required`
- `reason_json`
- `model_version`

---

## B. 시그널 엔진 세분화 (필수)
하나의 계산기로 몰지 말고 다음 서비스로 분리하라.

### 필수 서비스 구성
- `ScalpNewsSignalService` (단타)
- `MarketTrendSignalService` (중기/일매매)
- `ChartPositionStrategyService` (장기 대응/평단가)
- `LongTermDiscoveryService` (6개월 발굴)
- `SignalFusionService` (최종 통합 점수/행동결정)
- `PressureDetectionService` (연속 매수/매도 + 거래량 동일 판정)
- `RiskPolicyService` (몰빵 금지/비중/잠금)
- `ReanalysisLockService` (익절/손절 후 재진입 금지)
- `SignalReasonBuilder` (설명 가능한 reason_json 생성)

### 최종 통합 시그널 결정 규칙
최종 액션은 아래 중 하나로 표준화:
- `BUY_CANDIDATE`
- `SELL_CANDIDATE`
- `HOLD`
- `WATCH`
- `BUY_LOCK` (익절/손절 후 재분석 전 금지)

---

## C. 백테스트/모의매매 고도화 (필수)

### 백테스트에서 반드시 검증할 항목
1. 뉴스만 사용 vs 차트만 사용 vs 뉴스+차트 결합 성능 비교
2. 거래량 동일 판정 규칙 적용 전/후 성능 비교
3. 몰빵 금지 규칙 적용 전/후 MDD 비교
4. 재분석 잠금(`BUY_LOCK`) 적용 전/후 과매매 감소 효과
5. 평단가 낮추기 허용/비허용 성능 비교
6. 단타/중기/장기 엔진별 성능 분리 리포트
7. 6개월 발굴 후보의 후행 성과 추적 시뮬레이션

### 모의매매에서 반드시 구현할 항목
- 분할매수/분할매도 규칙 실행
- 익절/손절 발생 시 `BUY_LOCK` 전환
- 잠금 해제 전 매수 요청 차단
- 종목/테마/국가 비중 초과 주문 차단
- 신호는 있으나 유동성 부족 시 주문 차단
- 주문 차단 사유를 로그/DB/UI에 표시

---

## D. 대시보드 UI 확장 (전략정책 반영, 필수)

### 1) 분석 목적별 패널 추가
홈 또는 탭 구조로 아래 패널을 구현하라.

#### A. 최근 뉴스 검색 (단타)
- 최근 10분/30분/1시간 뉴스 필터
- 종목/테마별 뉴스 건수
- 호재/악재 확률
- 단타 후보 리스트 (`BUY_CANDIDATE`, `SELL_CANDIDATE`, `WATCH`)

#### B. 시장 동향 (중기/일 매매)
- 국가/테마별 시장 레짐 (`RISK_ON`, `RISK_OFF`, `MIXED`)
- 테마 강도 점수
- 최근 1주 뉴스/차트 동향 요약

#### C. 차트 대응 전략 (평단가 포함)
- 보유종목 기준
- 평단가 / 현재가 / 손익률
- 추가매수 허용 여부 (`avg_down_allowed`)
- 다음 분할매수 비중
- 위험 경고 표시

#### D. 6개월 발굴 후보
- 후보 랭킹
- 발굴 점수
- 최근 뉴스 증가율
- 차트 전환 초기 패턴 여부
- 발굴 사유 요약

### 2) 리스크/자금관리 패널 (필수)
- 총 투자금
- 종목별 비중
- 테마별 비중
- 국가별 비중
- 익절/손절 기준
- 현재 `BUY_LOCK` 상태 종목 목록
- 잠금 해제 예정 시각
- “몰빵 금지 정책 적용 중” 배지

### 3) 시그널 상세 팝업/드릴다운 (필수)
반드시 표시:
- 호재확률 / 악재확률
- 뉴스 근거 목록 (최근 1주 포함)
- 차트 근거 (추세/거래량/변동성)
- 연속 매수/매도 탐지 결과
- 거래량 동일 판정 여부
- 최종 액션 결정 사유
- 주문 허용/차단 사유

---

## E. API 계약 확장 (필수)

### 시그널/분석 조회 API
- `GET /api/insight/signals/scalp?country&theme&limit`
- `GET /api/insight/signals/swing?country&theme&limit`
- `GET /api/insight/signals/position?assetCode`
- `GET /api/insight/discovery?country&theme&period=6m&limit`
- `GET /api/insight/assets/{assetCode}/weekly-context`
- `GET /api/insight/assets/{assetCode}/pressure-analysis`

### 리스크/자금관리 API
- `GET /api/paper-trade/risk/portfolio`
- `GET /api/paper-trade/locks`
- `POST /api/admin/paper-trade/risk-policy`
- `POST /api/admin/paper-trade/capital-config`

### 응답에 반드시 포함할 필드
- `trace_id`
- `action`
- `combined_confidence`
- `good_news_probability`
- `bad_news_probability`
- `weekly_context_score`
- `risk_checks`
- `blocked_reason` (차단 시)

---

## F. 설정값 외부화 (필수)
다음 값은 하드코딩 금지. `application.yml` 및 ADMIN 설정 API로 관리 가능하게 구현하라.

### 전략 설정
- 뉴스 분석 윈도우 (10m, 30m, 1h, 1d, 1w)
- 차트 지표 기간 (MA 5/20/60/120 등)
- 거래량 동일 허용 오차
- 연속 매수/매도 탐지 윈도우
- 호재/악재 확률 임계치
- 시그널 쿨다운 시간

### 자금관리 설정
- 총 투자금
- 종목당 최대 비중
- 테마당 최대 비중
- 국가당 최대 비중
- 동시 보유 종목 수
- 분할매수 비중 배열
- 분할매도 비중 배열
- 익절/손절 %
- 재분석 잠금 시간

---

## G. 테스트 요구사항 (강화판, 40개 이상)
기존 테스트(30+)에 아래 항목을 추가하여 총 40개 이상 작성하라.

### 전략정책 반영 추가 테스트 (필수)
1. 뉴스+차트 둘 다 있어야 BUY 후보 생성
2. 뉴스만 좋고 차트 불량이면 BUY 차단
3. 차트만 좋고 뉴스 부재면 WATCH 처리
4. 최근 1주 뉴스 흐름 점수 계산 검증
5. 최근 1주 차트 동향 점수 계산 검증
6. `weekly_context_score` 반영 검증
7. 연속 매도 탐지 + 거래량 동일 => 악재 단정 금지
8. 연속 매수 탐지 + 거래량 동일 => 호재 단정 금지
9. 거래량 동일 허용오차 경계값 테스트
10. 몰빵 금지 규칙 위반 시 주문 차단
11. 테마 비중 초과 주문 차단
12. 국가 비중 초과 주문 차단
13. 동시 보유 종목 수 초과 차단
14. 분할매수 비중 합계 검증 (100%)
15. 분할매도 비중 합계 검증 (100%)
16. 익절 발생 시 BUY_LOCK 전환
17. 손절 발생 시 BUY_LOCK 전환
18. 잠금 상태에서 매수 요청 차단
19. 잠금 해제 조건 충족 전 재진입 차단
20. 잠금 해제 후 재분석 완료 시에만 매수 허용
21. 평단가 낮추기 허용 조건 충족 테스트
22. 평단가 낮추기 금지 조건(악재/추세붕괴) 테스트
23. 발굴 엔진 6개월 후보 랭킹 생성
24. 발굴 엔진 reason_json 생성 검증
25. UI 리스크 패널 노출값 정합성 검증
26. 시그널 상세 팝업 근거 표시 검증
27. 주문 차단 사유 UI 표시 검증
28. 백테스트 비교 리포트(정책 전/후) 생성 검증
29. 과매매 감소 지표 계산 검증
30. MDD 개선 비교 계산 검증

---

## H. 구현 산출물 (반드시 생성)
1. 아키텍처 문서 (분석 목적 4계층 포함)
2. Mermaid 다이어그램 (ER/시그널/백테스트/모의매매/잠금 플로우)
3. DB 마이그레이션 SQL
4. 엔티티/리포지토리/서비스/컨트롤러 코드
5. 시그널 엔진 세분화 코드 (단타/중기/차트대응/발굴/퓨전)
6. 리스크 정책/잠금 정책 코드
7. Quartz 배치/스케줄러
8. API 스펙 표 + JSON 예시
9. UI 확장 코드 (분석패널/리스크패널/상세팝업)
10. 백테스트/모의매매 코드
11. 테스트 케이스 표(40+)
12. 자기검증 체크리스트
13. 변경 파일/신규 파일 목록 표

---

## 수용 기준 (강제)
1. 기존 뉴스 기능이 유지된다.
2. 뉴스+시장데이터 결합 시그널이 생성/저장/조회된다.
3. 단타/중기/차트대응/6개월 발굴 4개 목적의 결과를 각각 조회 가능하다.
4. 거래량 동일 시 연속 매수/매도 신호 과대해석 방지 규칙이 동작한다.
5. 몰빵 금지/비중 제한/재분석 잠금 정책이 모의매매에서 강제된다.
6. 평단가 낮추기 허용/금지 조건이 구현된다.
7. 백테스트 비교 리포트(정책 전/후)가 생성된다.
8. 실주문은 기본 비활성 상태이며 Stub만 제공된다.

---

## 코딩 규칙
- Java 21 + Spring Boot + JPA + Quartz + jQuery 4.0 기준
- 기존 ErrorDto / trace_id / 보안 정책 / 캐시 정책 재사용
- 주석/설명은 한국어
- 민감정보 하드코딩 금지
- 최소 Mock 데이터 기반 end-to-end 동작 경로 완성
- “TODO만 남기는 코드” 금지

---

## 최종 출력 형식 (Codex 응답 형식 강제)
1. 변경 개요
2. 아키텍처 다이어그램 (mermaid)
3. DB 스키마/ER
4. 백엔드 코드 (핵심 파일 전체)
5. 프론트 코드 (핵심 파일 전체)
6. 설정 파일 변경점
7. API 스펙 표 + JSON 예시
8. 운영/보안/리스크 체크리스트
9. 테스트 케이스 표(40+)
10. 자기검증 체크리스트
11. 실행 방법 (local/server)
12. 변경 파일/신규 파일 목록 표

# PROMPT-7 (검증/정합성/감사 강화) — 뉴스+시장데이터 통합 자동매매 준비 시스템 신뢰도 고도화

## 목적
현재 구현된 글로벌 뉴스 기반 시장분석/자동매매 준비 시스템의 다음 문제를 해결하라.

### 현재 문제 (반드시 해결)
1. 국가별 화면에 대표 종목만 반복 노출되고 유니버스 확장/동적 선별이 약함
2. 실제 시장데이터 수집 성공 여부와 품질(누락/지연/중복/이상치)을 검증할 수 없음
3. 뉴스-가격 결합 시그널의 신뢰도/근거 추적이 부족함
4. 뉴스와 가격의 시간 정렬(타임 얼라인먼트) 검증이 없음
5. 백테스트는 가능하나 과최적화 방지 구조가 없음
6. 운영 중 전략/시그널/모의매매를 즉시 중지할 킬스위치 체계가 약함
7. DB 테이블/컬럼 설명(주석/코멘트)이 부족하여 유지보수성이 떨어짐

## 목표 상태
기존 뉴스 시스템 구조를 유지하면서, 아래를 추가/강화한다.
- 데이터 수집 품질 검증 구조 (검증 배치 + 진단 API + 진단 패널)
- 국가/테마별 동적 유니버스 선정 로직
- 뉴스-가격 시간정렬 검증 로직
- 시그널 감사(Audit) 로그 체계
- 백테스트 과최적화 방지 구조
- 운영 킬스위치/전략 비활성화 체계
- DDL/JPA 엔티티 테이블/컬럼별 주석(Comment) 추가

---

## 절대 원칙 (강제)
1. 기존 뉴스 수집/번역/분류/캐시/UI 동작을 깨지 않는다.
2. 뉴스 도메인과 시장데이터 도메인의 장애 전파를 차단한다.
3. UI보다 데이터 정합성/검증/감사/리스크 통제가 우선이다.
4. 실주문 기능은 기본 비활성 상태 유지 (`app.trade.live-enabled=false`)
5. 모든 진단/시그널/주문 관련 응답에는 `trace_id`를 포함한다.
6. 민감정보(API key/token/account)는 로그/응답/예외메시지에 출력하지 않는다.

---

## 구현 범위 (이번 PROMPT의 범위)
### A. 데이터 수집 검증/정합성 강화 (필수)
### B. 유니버스/티커/매핑 고도화 (필수)
### C. 시간정렬(Time Alignment) + 시그널 감사로그 (필수)
### D. 시그널 품질 규칙/확률/차트 룰셋 수치화 (필수)
### E. 백테스트 과최적화 방지 + 검증 리포트 (필수)
### F. 운영 킬스위치/전략 비활성화 + 진단 API/UI (필수)
### G. DB/JPA 주석(Comment) 전면 추가 (필수)

---

## A. 데이터 수집 검증/정합성 강화

### A-1. 수집 검증 전용 테이블 추가
기존 수집 이력 테이블과 별도로 품질/진단용 테이블을 추가하라.

#### 1) `market_data_quality_snapshot`
시장데이터 수집 품질 스냅샷(주기별 집계)
- `id` (PK)
- `snapshot_time_utc`
- `provider` (TOSS, MOCK, ...)
- `country`
- `theme`
- `asset_count_expected`
- `asset_count_collected`
- `quote_count_expected`
- `quote_count_collected`
- `bar_count_expected`
- `bar_count_collected`
- `missing_rate`            -- 누락률
- `delay_rate`              -- 지연률
- `duplicate_rate`          -- 중복률
- `anomaly_rate`            -- 이상치율
- `quality_score`           -- 종합 품질점수 (0~100)
- `summary_json`            -- 품질 요약/원인/샘플
- `created_at`
- `trace_id`

인덱스:
- `idx_mdq_snapshot_time_desc`
- `idx_mdq_provider_country_theme`
- `idx_mdq_quality_score`

#### 2) `market_data_gap_event`
수집 누락/지연/이상 이벤트 상세 로그
- `id` (PK)
- `event_time_utc`
- `asset_id` (FK -> asset_universe.id nullable)
- `provider`
- `timeframe` (nullable)
- `event_type` (MISSING_BAR, DELAYED_QUOTE, DUPLICATE_BAR, OUTLIER_PRICE, TIME_REVERSAL)
- `severity` (INFO, WARN, ERROR)
- `expected_time_utc`
- `actual_time_utc`
- `delay_seconds`
- `detail_json`
- `resolved` (bool)
- `resolved_at` (nullable)
- `trace_id`

인덱스:
- `idx_gap_event_time_desc`
- `idx_gap_event_asset_type`
- `idx_gap_event_severity_resolved`

#### 3) `api_response_audit`
외부 Provider 응답 감사(샘플링 저장)
- `id` (PK)
- `provider`
- `api_name`
- `request_time_utc`
- `response_time_utc`
- `latency_ms`
- `http_status`
- `request_hash`
- `response_hash`
- `record_count`
- `sample_payload_json`     -- 민감정보 제거된 샘플만 저장
- `success` (bool)
- `error_code`
- `trace_id`

인덱스:
- `idx_api_audit_provider_time_desc`
- `idx_api_audit_api_name`
- `idx_api_audit_success`

### A-2. 검증 배치/스케줄러 추가 (Quartz)
다음 배치를 추가하라.
- `MarketDataQualityAuditJob` (5분/10분)
- `MarketDataGapDetectionJob` (1분/5분)
- `ApiResponseAuditCleanupJob` (일 1회)
- `DataQualitySummaryJob` (일 1회)

배치 목적:
- 수집 성공 여부가 아니라 **수집 품질**을 수치화
- 누락/지연/중복/이상치 이벤트를 기록
- 품질 점수 계산 및 UI/API 노출

### A-3. 품질 검증 규칙 (수치화)
반드시 구현:
- **누락률** = 기대건수 대비 미수집 건수
- **지연률** = 허용 지연시간 초과 건수 비율
- **중복률** = 동일 키(자산/시각/timeframe) 중복 비율
- **이상치율** = 전 bar 대비 급격한 비정상 값 (설정값 기준)
- **시간역전 탐지** = `actual_time < last_seen_time` 또는 event time 역전

설정 외부화:
- `app.market.quality.max-quote-delay-seconds`
- `app.market.quality.max-bar-delay-seconds`
- `app.market.quality.outlier-threshold-pct`
- `app.market.quality.audit-sample-rate`
- `app.market.quality.minimum-quality-score`

---

## B. 유니버스/티커/매핑 고도화 (대표종목 반복 문제 해결 핵심)

### B-1. 유니버스 동적 선정 구조
현재의 대표 종목 고정 노출 문제를 해결하기 위해 `asset_universe`를 단순 마스터가 아니라 **정책 기반 유니버스**로 운영하라.

추가 필드 (`asset_universe` 확장)
- `selection_source` (MANUAL, MARKET_CAP, VOLUME, THEME_LEADER, WATCHLIST, DISCOVERY)
- `selection_score` (0~100)
- `market_cap_rank` (nullable)
- `avg_volume_rank` (nullable)
- `is_core_asset` (bool)         -- 국가/테마 대표 핵심 자산 여부
- `is_watchlist_asset` (bool)    -- 사용자 관심 종목 여부
- `display_weight` (int)         -- UI 노출 우선순위
- `last_verified_at`             -- 티커/종목명 검증 시각
- `verification_status` (VERIFIED, UNVERIFIED, FAILED)

### B-2. 유니버스 리빌드 배치
추가 배치:
- `UniverseRebuildJob` (일 1회 + 관리자 수동 실행)

동작:
1. 국가별/테마별 후보 자산 집합 생성
2. 시가총액/거래량/테마 관련성/최근 뉴스 언급량 기반 점수 계산
3. 고정 대표종목만 반복되지 않도록 다양성 규칙 적용
4. UI 표시용 “핵심 자산”과 “후보 자산”을 분리 생성

다양성 규칙 예시 (강제 구현)
- 동일 기업군/동일 티커 타입 반복 노출 제한
- 국가/테마별 최소 표시 종목 수 보장
- 최근 노출 빈도 과다 종목 패널티 부여
- 품질점수 낮은 자산 제외

### B-3. 티커 사전/별칭 검증 자동화
`TickerAliasDictionary` 검증 배치/테스트 추가
- `TickerAliasVerificationJob` (일 1회)
- 검증 항목:
  - 티커 형식 유효성
  - 거래소 코드 정합성
  - 종목명/티커 매핑 정합성
  - 중복 별칭 충돌
  - 한글명/영문명/약칭 충돌

결과 저장:
- `ticker_alias_audit` 테이블 추가 또는 기존 audit 테이블 JSON 저장

### B-4. 뉴스-자산 매핑 품질 평가
`news_asset_link` 생성 후 샘플링 검증 기능 추가
- 직접언급 매칭 정확도 샘플
- 테마매칭 오탐률 샘플
- 국가+테마 매칭 과확장 여부
- link_score 분포 분석

결과를 `mapping_quality_report` (테이블 또는 JSON 보고서)로 저장하고 API/UI에서 조회 가능하게 하라.

---

## C. 시간정렬(Time Alignment) + 시그널 감사로그

### C-1. 시간정렬 필드 정규화 (뉴스/시장데이터 공통)
뉴스와 시장데이터의 시간 혼선을 방지하기 위해 다음 시간을 명확히 분리 저장/사용하라.

뉴스 관련 필수 시간 (기존 news 테이블/도메인 확장)
- `published_at_utc`       -- 원문 기사 발행 시각
- `fetched_at_utc`         -- 수집 시각
- `translated_at_utc`      -- 번역 완료 시각
- `indexed_at_utc`         -- 시그널/검색 인덱싱 완료 시각
- `event_time_source`      -- 시간 기준(source field) 표시

시장 데이터 관련 필수 시간
- `quote_time_utc` / `bar_time_utc`   -- 시장 이벤트 시각
- `ingested_at`                       -- 시스템 저장 시각

### C-2. 시간정렬 검증 로직 (필수)
다음 검증을 추가하라.
- 뉴스 발행시각 > 수집시각이면 경고
- 번역완료가 늦은 뉴스는 단타 시그널 감점
- 시그널 계산 시점보다 미래시각 데이터 사용 금지 (look-ahead 방지)
- 뉴스-가격 반응 윈도우 설정 (예: 뉴스 후 5분/15분/1시간)

설정 외부화:
- `app.signal.news-price-alignment-window-minutes`
- `app.signal.translation-delay-penalty-threshold-seconds`
- `app.signal.block-future-data=true`

### C-3. 시그널 감사로그 테이블 추가 (강제)
#### `signal_audit_log`
시그널 생성 전/중/후 판단 과정을 감사용으로 남긴다.
- `id` (PK)
- `signal_id` (FK -> trading_signal.id nullable)
- `asset_id` (FK)
- `audit_time_utc`
- `engine_type` (SCALP, SWING, POSITION, DISCOVERY, FUSION)
- `input_snapshot_json`        -- 입력값 스냅샷(뉴스수, 점수, 가격지표 등)
- `rule_hits_json`             -- 적용된 룰 목록/결과
- `risk_checks_json`           -- 리스크 점검 결과
- `decision_before_risk`       -- 위험관리 전 액션
- `decision_after_risk`        -- 위험관리 후 액션
- `blocked_reason`
- `confidence_before`
- `confidence_after`
- `model_version`
- `trace_id`

인덱스:
- `idx_signal_audit_asset_time_desc`
- `idx_signal_audit_engine_type`
- `idx_signal_audit_signal_id`

목적:
- “왜 HOLD/BUY_LOCK/SELL_CANDIDATE가 나왔는지” 재현 가능하게 만들 것

---

## D. 시그널 품질 규칙/확률/차트 룰셋 수치화 (Codex 임의 구현 방지)

### D-1. 호재/악재 확률 계산 규칙 명시 (RULE_V1)
Codex가 임의 점수식을 만들지 않도록 아래를 강제 구현하라.

필수 파라미터 (설정 외부화)
- `min_news_count_for_probability`
- `news_trust_score_min`
- `positive_sentiment_threshold`
- `negative_sentiment_threshold`
- `news_decay_half_life_minutes`
- `probability_calculation_mode=RULE_V1`

필수 계산 결과 저장
- `good_news_probability`
- `bad_news_probability`
- `news_confidence`
- `probability_reason_breakdown_json`

규칙 예시 (구현 가능한 수준으로 문서화 + 코드 주석):
- 최근 N개 뉴스 중 신뢰도 하한 이상만 사용
- 시간감쇠 적용 (최근 뉴스 가중치 높음)
- 긍/부정 감성 가중 평균으로 점수화
- 표본 수 부족 시 확률 계산 금지 또는 confidence 낮춤

### D-2. 차트 룰셋 V1 고정 (필수)
“차트 분석”을 개념이 아니라 룰셋으로 구현하라.

필수 지표
- 이동평균: MA5, MA20, MA60, MA120
- ATR (변동성)
- 거래량 평균 대비 비율
- 최근 N봉 고점/저점 갱신 여부
- 추세 기울기(최소 단순 방식)

필수 판정 규칙 예시 (문서+코드 주석으로 명시)
- `LONG_BIAS` 조건
- `SHORT_BIAS` 조건
- `trend_breakdown_severe` 조건
- `avg_down_allowed` 조건에서 차트 측 판정 기준

필수 파일 생성:
- `docs/SignalRules.md`
- `docs/ChartRuleSetV1.md`

### D-3. 거래량 동일/연속 매수·매도 탐지 규칙 강화
이미 정의된 정책을 실제 수치로 고정하고 주석화하라.
- `volume_same_tolerance_pct`
- `pressure_window_bars`
- `volume_window_bars`
- `buy/sell pressure detection threshold`

필수 저장 필드/로그
- `buy_pressure_detected`
- `sell_pressure_detected`
- `volume_regime_same`
- `pressure_reason_json`

---

## E. 백테스트 과최적화 방지 + 검증 리포트

### E-1. 백테스트 검증 모드 추가 (필수)
과최적화 방지 구조를 추가하라.

필수 설정
- `backtest.validation_mode` (NONE, TRAIN_TEST_SPLIT, WALK_FORWARD)
- `backtest.out_of_sample_required=true`
- `backtest.minimum_trade_count_threshold`
- `backtest.overfit_warning_enabled=true`

필수 기능
- In-sample / Out-of-sample 성과 분리
- Walk-forward 구간별 결과 저장
- 거래횟수 부족 경고
- 파라미터 과민감 경고 (간단 버전 가능)

### E-2. 비교 리포트 생성 (정책 전/후)
반드시 아래 비교를 자동 생성하라.
1. 정책 도입 전(Baseline) vs 정책 도입 후
2. 뉴스만 vs 차트만 vs 뉴스+차트
3. 거래량 동일 판정 규칙 적용 전/후
4. 몰빵 금지 적용 전/후
5. BUY_LOCK 적용 전/후
6. 평단가 낮추기 허용/금지 비교

결과 저장:
- `strategy_run.result_summary_json` 확장
- `backtest_comparison_report` 테이블 또는 JSON 보고서 파일 저장

---

## F. 운영 킬스위치/전략 비활성화 + 진단 API/UI

### F-1. 운영 킬스위치 테이블/설정 추가
#### `system_feature_toggle`
- `id` (PK)
- `feature_key` (SIGNAL_GENERATION, PAPER_TRADING, SCALP_ENGINE, DISCOVERY_ENGINE, UNIVERSE_REBUILD, MARKET_COLLECTOR ...)
- `enabled` (bool)
- `scope_type` (GLOBAL, COUNTRY, THEME, ASSET)
- `scope_value` (nullable)
- `reason`
- `updated_by`
- `updated_at`
- `trace_id`

인덱스:
- `uq_feature_toggle(feature_key, scope_type, scope_value)`
- `idx_feature_toggle_enabled`

기능:
- 전체/국가별/테마별/자산별 전략 중지
- 모의매매만 중지
- 시그널 엔진 중 특정 엔진만 중지

### F-2. 진단 API 추가 (필수)
#### 데이터 수집/품질 진단
- `GET /api/admin/diagnostics/market-collection/summary`
- `GET /api/admin/diagnostics/market-collection/gaps`
- `GET /api/admin/diagnostics/market-collection/provider-audit`
- `GET /api/admin/diagnostics/market-collection/quality-score`

#### 유니버스/매핑 진단
- `GET /api/admin/diagnostics/universe`
- `GET /api/admin/diagnostics/universe/diversity`
- `GET /api/admin/diagnostics/ticker-alias`
- `GET /api/admin/diagnostics/news-asset-mapping`

#### 시그널/감사 진단
- `GET /api/admin/diagnostics/signals/audit`
- `GET /api/admin/diagnostics/signals/alignment`
- `GET /api/admin/diagnostics/signals/confidence-distribution`

#### 킬스위치/운영 통제
- `GET /api/admin/feature-toggles`
- `POST /api/admin/feature-toggles`
- `PATCH /api/admin/feature-toggles/{id}`

응답 규격:
- `{ data, meta, trace_id }`
- `meta`에 `generated_at`, `source_window`, `warning_count` 포함

### F-3. 진단 UI 패널 추가 (필수)
기존 전략 패널 외에 관리자용 진단 탭/패널 추가

#### 1) 수집 품질 패널
- 기대건수 vs 수집건수
- 누락률/지연률/중복률/이상치율
- 품질점수 추이
- provider 상태 배지
- 최근 gap 이벤트 리스트

#### 2) 유니버스 진단 패널
- 국가/테마별 유니버스 개수
- 핵심자산/관심자산/발굴후보 분포
- 대표종목 편중도(노출빈도 상위)
- 다양성 규칙 위반 경고

#### 3) 시그널 신뢰도/감사 패널
- confidence 분포
- HOLD/BUY/WATCH 비율
- 차단 사유 분포 (`BUY_LOCK`, `LIQUIDITY_LOW`, `RISK_LIMIT_EXCEEDED`)
- signal_audit_log 상세 조회 (trace_id 기반)

#### 4) 킬스위치 패널
- 기능별 ON/OFF 상태
- 범위(전체/국가/테마/자산)
- 변경 사유/변경자/시각
- 즉시 적용 여부 표시

---

## G. DB/JPA 주석(Comment) 전면 추가 (강제)

### G-1. SQL DDL 주석 규칙 (반드시 적용)
모든 신규/변경 테이블에 대해:
1. `COMMENT ON TABLE ... IS '...'`
2. `COMMENT ON COLUMN ... IS '...'`
3. 인덱스 목적 주석(마이그레이션 파일 상단 주석 블록) 추가

예시 형식 (반드시 이 방식으로 생성)
```sql
CREATE TABLE asset_universe (
    id BIGSERIAL PRIMARY KEY,
    asset_code VARCHAR(32) NOT NULL,
    asset_name VARCHAR(200) NOT NULL,
    asset_type VARCHAR(30) NOT NULL,
    theme VARCHAR(50),
    country VARCHAR(10),
    exchange_code VARCHAR(20),
    currency VARCHAR(10),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    priority INTEGER NOT NULL DEFAULT 0,
    selection_source VARCHAR(30),
    selection_score NUMERIC(5,2),
    market_cap_rank INTEGER,
    avg_volume_rank INTEGER,
    is_core_asset BOOLEAN NOT NULL DEFAULT FALSE,
    is_watchlist_asset BOOLEAN NOT NULL DEFAULT FALSE,
    display_weight INTEGER NOT NULL DEFAULT 0,
    last_verified_at TIMESTAMPTZ,
    verification_status VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE asset_universe IS '뉴스/시장데이터/시그널 분석에 사용하는 자산(종목/원자재/ETF 등) 유니버스 마스터';
COMMENT ON COLUMN asset_universe.id IS '자산 유니버스 PK';
COMMENT ON COLUMN asset_universe.asset_code IS '자산 코드(티커/거래소 종목코드/내부 표준코드)';
COMMENT ON COLUMN asset_universe.asset_name IS '자산명(한글 또는 영문 표준명)';
COMMENT ON COLUMN asset_universe.asset_type IS '자산 유형(EQUITY, ETF, COMMODITY, INDEX 등)';
COMMENT ON COLUMN asset_universe.theme IS '테마 분류(AI, 반도체, 방산, 우주, 에너지 등)';
COMMENT ON COLUMN asset_universe.country IS '국가 코드(KR, US, JP 등)';
COMMENT ON COLUMN asset_universe.exchange_code IS '거래소 코드(KRX, NASDAQ, NYSE 등)';
COMMENT ON COLUMN asset_universe.currency IS '거래 통화(KRW, USD 등)';
COMMENT ON COLUMN asset_universe.enabled IS '수집/분석/표시 대상 사용 여부';
COMMENT ON COLUMN asset_universe.priority IS '기본 우선순위(클수록 우선)';
COMMENT ON COLUMN asset_universe.selection_source IS '유니버스 선정 방식(MANUAL, MARKET_CAP, VOLUME 등)';
COMMENT ON COLUMN asset_universe.selection_score IS '유니버스 선정 점수(0~100)';
COMMENT ON COLUMN asset_universe.market_cap_rank IS '국가/시장 기준 시가총액 순위';
COMMENT ON COLUMN asset_universe.avg_volume_rank IS '평균 거래량 순위';
COMMENT ON COLUMN asset_universe.is_core_asset IS '국가/테마 대표 핵심 자산 여부';
COMMENT ON COLUMN asset_universe.is_watchlist_asset IS '사용자 관심자산 여부';
COMMENT ON COLUMN asset_universe.display_weight IS 'UI 노출 우선순위 가중치';
COMMENT ON COLUMN asset_universe.last_verified_at IS '티커/종목명 매핑 검증 완료 시각';
COMMENT ON COLUMN asset_universe.verification_status IS '검증 상태(VERIFIED, FAILED 등)';
COMMENT ON COLUMN asset_universe.created_at IS '생성 시각';
COMMENT ON COLUMN asset_universe.updated_at IS '수정 시각';

# PROMPT-8-REBUILD (실사용형 주식분석/추천 고도화 재설계)

## 목적 (이번 작업의 핵심)
기존 Global News Dashboard의 뉴스 수집/번역/분류/포털 UI는 유지하면서,
현재의 참고용/중복형 주식 시그널 화면을 **실제 시세 기반 + 뉴스 연계 + 설명 가능한 분석 구조**로 재구성한다.

최종 목표는 다음과 같다.
1. 토스MCP 또는 키움증권 REST API 기반 실제 주식/자원/ETF/테마 데이터 수집 및 DB 적재
2. 뉴스 + 시세 + 규칙 + 경량모델(RAG/LLM 보조) 기반 분석 엔진 구축
3. 화면에서 “실시간 뉴스 + 종목 상태 + 근거 + 액션 제안”을 제공하는 AI 비서형 UX 구축
4. 수동 매매 우선 + 관리자 승인 기반 자동화 확장 가능한 구조 확보
5. 운영/감사/품질/리스크 통제가 가능한 관리자 진단 체계 유지 및 강화

---

## 현재 문제 (반드시 해결)
1. 국가별 대표 종목만 반복 노출되어 유니버스 다양성이 부족함
2. 상세보기가 호재50/악재50 수준으로 정보 가치가 낮음
3. 모든 종목 확률값이 50%±5% 근처로 수렴하여 차별성이 없음
4. 뉴스-종목 연결 및 호재/악재 분석 근거가 부족함
5. 핵심 분야(자원/방산/우주/AI/반도체/로봇/에너지) 선택이 전략패널에 제대로 반영되지 않음
6. 동일 종목/동일 정보 중복 노출이 많음
7. 리스크/자금관리 패널이 실질적 판단에 기여하지 못함
8. DB 저장 후 시그널/리스크가 고정된 것처럼 보이고 재계산/갱신 신뢰성이 낮음
9. 실제 매매/승인 흐름으로 확장 가능한 구조가 아님

---

## 절대 원칙 (강제)
1. 기존 뉴스 포털 기능(수집/번역/분류/캐시/UI)을 깨지 않는다.
2. 뉴스 도메인 장애가 시장데이터/시그널/주문 도메인으로 전파되지 않게 분리한다.
3. 실주문은 기본 비활성 상태 유지 (`app.trade.live-enabled=false`).
4. 실주문 연동 구현 시에도 **관리자 승인 없이는 주문 실행 금지**를 기본값으로 한다.
5. 모든 분석/시그널/추천/주문/진단 응답에 `trace_id` 포함.
6. 민감정보(API KEY, APPKEY, SECRET, TOKEN, 계좌번호)는 로그/예외/응답에 노출 금지.
7. UI 개선보다 데이터 정합성/검증/감사/설명가능성을 우선한다.
8. 신규/변경 테이블 및 컬럼에는 반드시 DB 코멘트(Comment)와 JPA 주석을 추가한다.

---

## 입력 컨텍스트 (반드시 참고)
- 기존 `prompt.md`와 `work_timeline.md`를 읽고 현재 구현 범위/완료내역을 파악한 뒤 작업한다.
- 특히 다음 기구현 항목은 재사용/확장한다:
  - 뉴스 포털 홈/카테고리 UI
  - 개인용 주식 시그널 패널 (기존 휴리스틱 엔드포인트)
  - 관리자 진단 패널/trace 상세/킬스위치
  - 시그널 감사/품질진단/유니버스 진단 골격
- 기존 화면을 전부 뒤엎지 말고, **실제 데이터 기반 동작으로 치환/강화**한다.

---

# A. Git 작업 절차 (반드시 수행)
## A-1. 현재 작업물 보존 커밋 (main 또는 현재 브랜치)
1. 현재까지 작업된 내용 전체를 검토하고 변경 파일에 주석/설명 정리
2. 커밋 메시지 예시:
   - `chore: checkpoint current news dashboard + signal diagnostics UI before trading rebuild`
3. 원격에 push 한다.

## A-2. 고도화 작업용 신규 브랜치 생성
1. 새 브랜치 생성:
   - `feature/ai-stock-assistant-rebuild`
2. 해당 브랜치에서만 이후 작업 진행
3. 커밋 단위는 기능별로 분리:
   - `feat(data): ...`
   - `feat(signal): ...`
   - `feat(rag): ...`
   - `feat(ui): ...`
   - `feat(admin): ...`
   - `docs(db): add comments for tables/columns`

---

# B. 시장데이터 수집 구조 재정립 (실제 시세 기반)
## B-1. Provider 추상화 계층 추가 (필수)
시장데이터 제공자를 교체 가능하게 설계한다.

### 인터페이스
- `MarketDataProvider`
  - `fetchQuotes(...)`
  - `fetchBars(...)`
  - `fetchAssetMeta(...)`
  - `healthCheck()`

### 구현체 (1차)
- `TossMcpMarketDataProvider` (가능한 범위)
- `KiwoomRestMarketDataProvider` (가능한 범위)
- `MockMarketDataProvider` (로컬/테스트용)

### 정책
- provider별 응답 포맷 차이를 내부 표준 DTO로 정규화
- 수집 실패/지연/빈응답/부분응답을 `market_provider_job`, 품질감사 로그에 기록
- provider 장애 시 뉴스 포털/웹 전체 렌더는 정상 유지

## B-2. 수집 대상(유니버스) 확장 정책 (핵심분야 반영)
현재의 국가별 대표주 반복 노출 문제를 해결하기 위해 유니버스를 정책 기반으로 운영한다.

### 핵심 분야 (필수 반영)
- 금/은/구리 등 자원
- 방산
- 우주
- AI
- 반도체
- 로봇
- 에너지

### 유니버스 구성 방식
유니버스를 최소 4개 레이어로 분리:
1. `CORE` : 국가/섹터 대표 핵심 종목
2. `WATCHLIST` : 사용자 관심 종목
3. `THEME_LEADER` : 테마 선도주
4. `DISCOVERY` : 발굴 후보 (중복노출 제한 대상)

### 요구사항
- 국가/테마/전략 목적(단타/중기/차트/발굴)에 따라 유니버스가 달라져야 한다.
- 전략패널 4개(단타/중기/차트대응/6개월 발굴)에 동일 종목이 반복되지 않도록 **중복 억제 정책** 적용.
- 동일 종목 반복 노출 시 `repeated_reason`를 내부 로그/진단에 남긴다.

---

# C. DB/도메인 재설계 및 코멘트 추가 (강제)
## C-1. 기존 테이블 재사용 + 확장
기존 prompt/work_timeline 기반의 테이블을 재사용하되 실사용 항목을 추가/보정한다.
(예: `asset_universe`, `market_price_bar`, `market_quote_snapshot`, `news_asset_link`, `trading_signal`, `strategy_run`, `market_provider_job`, `signal_audit_log`, `system_feature_toggle` 등)

## C-2. 신규/확장 필드 (필수)
### `asset_universe`
- `asset_type` (STOCK, ETF, INDEX, COMMODITY, FX, CRYPTO)
- `market` (KRX, NASDAQ, NYSE ...)
- `country_code`
- `theme_code`
- `universe_layer` (CORE, WATCHLIST, THEME_LEADER, DISCOVERY)
- `selection_source`
- `selection_score`
- `display_weight`
- `is_active`
- `is_user_watch`
- `is_trade_enabled`
- `dup_exposure_cooldown_minutes`
- `last_signal_generated_at`
- `last_quote_received_at`
- `last_news_linked_at`

### `market_quote_snapshot`
- `price`
- `change_amount`
- `change_rate_pct`
- `volume`
- `turnover_amount`
- `market_cap` (nullable)
- `provider_name`
- `provider_quote_time_utc`
- `ingested_at_utc`
- `quality_score`
- `is_delayed`
- `trace_id`

### `market_price_bar`
- `timeframe` (1m,5m,15m,1h,1d)
- `bar_time_utc`
- `open/high/low/close`
- `volume`
- `provider_name`
- `ingested_at_utc`
- `is_gap_fill`
- `quality_flags_json`

### `news_asset_link`
- `link_confidence`
- `link_method` (RULE, DICT, NER, LLM, HYBRID)
- `keyword_hits_json`
- `ticker_alias_hit`
- `theme_match_score`
- `event_type` (EARNINGS, CONTRACT, REGULATION, ACCIDENT, SUPPLY_CHAIN, MACRO, PRODUCT, RUMOR ...)
- `impact_direction` (POSITIVE, NEGATIVE, NEUTRAL, MIXED)
- `impact_horizon` (SCALP, SWING, POSITION)
- `trace_id`

### `trading_signal`
(기존 필드 유지 + 실사용성 강화)
- `strategy_type` (SCALP, SWING, POSITION, DISCOVERY, FUSION)
- `action` (BUY, SELL, HOLD, WATCH, REDUCE, AVOID)
- `signal_score`
- `good_news_probability`
- `bad_news_probability`
- `news_confidence`
- `chart_confidence`
- `market_regime_confidence`
- `combined_confidence`
- `expected_horizon`
- `entry_zone_min`
- `entry_zone_max`
- `take_profit_zone_json`
- `stop_loss_zone_json`
- `position_size_suggestion_pct`
- `risk_reward_ratio`
- `reason_json`
- `top_positive_factors_json`
- `top_negative_factors_json`
- `explain_text`
- `recalc_due_at_utc`
- `expires_at_utc`
- `trace_id`

### `signal_audit_log`
기존 설계 유지 + 아래 항목 보강
- `data_freshness_json`
- `news_alignment_result_json`
- `dedup_result_json`
- `universe_selection_snapshot_json`
- `rag_context_refs_json`
- `latency_ms_total`

## C-3. DB 코멘트/컬럼 코멘트 (강제)
모든 신규/변경 테이블에 반드시 적용:
- `COMMENT ON TABLE ...`
- `COMMENT ON COLUMN ...`
- JPA Entity/Field 한글 주석 추가
- migration SQL 내 주석과 엔티티 주석의 의미 일치

### 수용 기준
- schema migration 실행 후 DB 메타에서 테이블/컬럼 설명 조회 가능
- “이 테이블이 무엇을 저장하는지 / 이 컬럼이 무엇인지”를 코드 없이 DB만 보고 파악 가능

---

# D. 뉴스-종목 매핑 및 호재/악재 분석 고도화 (핵심)
## D-1. 뉴스-종목 매핑 파이프라인 (필수)
현재 단순 국가 대표주 매칭을 금지하고 아래 단계로 매핑한다.

1. 티커/종목명 사전 매칭 (alias dictionary)
2. 한국어/영문 종목명/약칭/브랜드명/제품명 룰 매칭
3. 테마 키워드 매칭 (반도체/방산/에너지 등)
4. 이벤트 유형 분류 (수주, 규제, 실적, 사고, 공급망, 금리 등)
5. LLM/RAG 보조 판정 (선택적, 저비용 경량모델)
6. `news_asset_link`에 링크 근거 저장

### 요구사항
- 뉴스 1건이 복수 종목과 연결될 수 있어야 함
- 종목 1개에 대해 최근 1시간/1일/1주 연결 뉴스 집계 가능해야 함
- 동일 기사 중복 링크는 제거 또는 감점

## D-2. 호재/악재 확률 계산 재구성 (50:50 고정 문제 해결)
확률값이 50% 근처에 수렴하는 문제를 해결하기 위해, 확률 계산을 단일 감성점수가 아니라 다중 피처 결합으로 구성한다.

### 입력 피처 (예시, 필수 반영)
- 최근 뉴스량 변화율 (baseline 대비)
- 이벤트 유형별 가중치
- 동일 이벤트 중복도 (중복 기사 감점)
- 출처 신뢰도/검증상태
- 가격 반응(뉴스 이후 5m/15m/1h)
- 거래량 변화율
- 변동성 레짐
- 시장 전반 리스크(지수/섹터)
- 번역 지연 패널티
- 시간정렬 검증 결과 (look-ahead 방지)
- 과거 동일 유형 이벤트 후 반응 통계 (1~2년 범위 내)

### 출력 원칙
- `good_news_probability` + `bad_news_probability` = 1.0 강제 금지
- 중립/불확실성 상태를 표현할 수 있어야 함 (`uncertainty_score` 추가 가능)
- `confidence`와 `probability`를 분리해 저장/표시
- 확률과 함께 반드시 “주요 근거 Top N” 제공

---

# E. 전략 엔진 분리 및 중복 억제 (UI 실효성 개선)
## E-1. 전략 엔진 분리 (필수)
다음 서비스를 분리 구현:
- `ScalpStrategyService` (단타)
- `SwingStrategyService` (중기/일매매)
- `ChartResponseStrategyService` (장기 전 대응/평단 관리)
- `DiscoveryStrategyService` (6개월 발굴)
- `SignalFusionService` (통합 추천/정렬)

## E-2. 전략별 선정 대상 규칙 (필수)
### 최근 뉴스 검색(단타)
- 단기 뉴스 이벤트 반응 중심
- 실시간성/뉴스-가격 시간정렬 가중치 높음
- stale 데이터 차단 강제

### 시장 동향(중기/일매매)
- 뉴스 + 일봉/시간봉 추세 + 섹터 흐름
- HOLD/WATCH 남발 금지, 액션 분포 균형 필요

### 차트 대응 전략(평단 관리/장기 전 대응)
- 분할매수/재진입/재분석 락(BUY_LOCK) 로직 실질화
- “매수 압력/매도 압력”은 거래량 동일 시 자동 호재/악재 판정 금지
- 사용자가 준 규칙 반영:
  - 일정 구간 지속 매도 발생 → 거래량 동일이면 악재로 단정 금지
  - 일정 구간 지속 매수 발생 → 거래량 동일이면 호재로 단정 금지

### 6개월 발굴 후보
- 단기 급등 뉴스보다 구조적 테마/수급/지속성 점수 우선
- 최근 1~2년 내 데이터만 사용
- 테마 다양성 강제 (한 테마 독점 금지)

## E-3. 중복 제거/다양성 규칙 (필수)
전략 패널 간 동일 종목 반복 노출 방지:
- 동일 새로고침 시 패널 간 중복 최대 1회까지만 허용 (설정 가능)
- 동일 종목 반복 노출 쿨다운 적용
- 국가/테마/시총/유동성/변동성 분산 기준 반영
- UI 응답에 `dedup_applied`, `diversity_score`, `selection_reason` 포함

---

# F. 리스크/자금관리 패널 실질화 (무의미 패널 제거)
## F-1. 리스크 패널을 “운영 판단용”으로 재설계
현재의 추상적 패널 대신 아래를 표시:
1. 현재 전략별 추천건수 / 차단건수
2. 차단 사유 분포 (BUY_LOCK, DATA_STALE, LOW_LIQUIDITY, NEWS_UNCERTAIN, RISK_LIMIT ...)
3. 종목당 최대 비중 규칙 적용 상태
4. 일손실/누적손실 기준 모의매매 차단 상태
5. 재분석 대기 종목 목록 (`reanalysis_lock_required=true`)
6. 데이터 품질 저하로 신뢰도 하향된 종목 목록

## F-2. 사용자 투자금 기반 제안 (참고용)
사용자 입력 투자금(예: 100만원) 기준:
- 권장 1회 진입 비중 %
- 분할매수 단계별 비중 %
- 익절/손절 기준 %
- 손절/익절 이후 재매수 금지 기간(재분석 락)
- 포트폴리오 분산 경고 (몰빵 방지)

※ 기본값은 “참고용”이며 실주문 자동 실행과 분리

---

# G. RAG + 경량모델 기반 분석 보조 (서버부하 최소화)
## G-1. RAG 적용 범위 (필수)
RAG는 “최종 판단 엔진 대체”가 아니라 “설명 보조/근거 요약/뉴스 이벤트 해석 보조”로 사용한다.

### 활용 대상
- 종목 상세보기의 근거 요약
- 왜 WATCH/HOLD/BUY인지 설명 생성
- 뉴스 이벤트 영향 해석 (단기/중기/중장기)
- 관리자 trace 상세의 사람이 읽을 수 있는 요약

### 비활성/차단 조건
- 데이터 신선도 부족
- 뉴스-종목 링크 근거 부족
- 토큰/응답시간 초과
- 킬스위치 OFF

## G-2. 경량 모델/운영 기준
- CPU 또는 저사양 환경에서 동작 가능한 경량 모델 우선
- 동기 요청 블로킹 최소화 (비동기/캐시)
- 요청당 timeout, fallback, circuit-breaker 필수
- 모델 출력은 반드시 구조화(JSON) 검증 후 저장

## G-3. RAG 저장/감사
- RAG 입력 컨텍스트 참조 목록 저장 (`rag_context_refs_json`)
- 모델 버전/프롬프트 버전 저장 (`model_version`, `prompt_version`)
- hallucination 방지를 위해 원문 근거 없는 단정 문장 금지
- LLM 결과는 규칙 엔진 결과를 덮어쓰지 않고 보조 설명/보정점수로만 사용 (설정 가능)

---

# H. AI 비서형 UI/UX 재설계 (뉴스 + 종목 실황)
## H-1. 홈 화면 역할 분리
홈은 “뉴스 포털 + 요약 시그널 진입” 역할로 유지하고,
실사용 분석은 별도 뷰로 분리:
- `/assistant` 또는 `view/stockAssistant`

## H-2. AI 주식비서 화면 (필수)
다음 구성으로 설계/구현:
1. 상단 상태바
   - 시장 상태(개장/장마감)
   - 데이터 수집 상태
   - provider 상태
   - 자동분석 ON/OFF
   - 모의매매/실주문 모드 상태(기본 모의)
2. 관심 종목 패널
   - 현재가, 등락률, 거래량, 당일 range
   - 뉴스 개수(1h/24h/7d)
   - 추천 액션(BUY/WATCH/HOLD/SELL/AVOID)
   - 신뢰도/리스크 배지
3. 전략 탭
   - 단타 / 중기 / 차트대응 / 6개월발굴
   - 탭별 정렬 기준과 중복억제 반영
4. 종목 상세 패널
   - 가격/등락률/최근 바 차트 요약
   - 연결 뉴스 목록 및 이벤트 유형
   - 호재/악재 확률 + 불확실성 + 근거 Top N
   - 리스크 체크 결과
   - 추천 액션/진입구간/익절/손절/분할전략 (참고용)
5. AI 비서 대화 영역 (선택)
   - “왜 이 종목이 WATCH인가?”
   - “최근 1주 악재 요약해줘”
   - “반도체 섹터에서 지금 리스크 낮은 후보 보여줘”
   - 모든 답변은 근거/trace 링크 포함

## H-3. 중복/무의미 표시 제거 (필수)
- 동일 종목 반복 카드 렌더 최소화
- 동일 수치 반복(`0.500`, `0.500`, `-0.003`)만 나오는 경우 빈상태/품질경고로 표시
- 데이터 부족 시 “중립(데이터 부족)”을 명확히 표시하고 추천 액션 생성 금지

---

# I. 관리자/운영 기능 강화 (기존 진단 패널 활용)
## I-1. 기존 관리자 진단 패널 고도화
기존 진단 탭/trace 조회/킬스위치 구조를 재사용하고 아래 진단 추가:
1. 종목 시세 수집 성공률/지연률/중복률/결측률
2. provider별 품질 점수 및 최근 장애 이력
3. 유니버스 다양성/편중도/중복노출 통계
4. 뉴스-종목 매핑 성공률/미매핑률/오탐률(샘플 검토)
5. 전략별 액션 분포/차단 사유 분포
6. RAG 호출 성공률/지연/timeout/fallback 비율
7. 모의매매 성과(참고) 및 과매매 경고

## I-2. 킬스위치(Feature Toggle) 세분화
기존 토글 구조를 확장:
- `MARKET_COLLECTOR`
- `NEWS_ASSET_LINKER`
- `SIGNAL_SCALP`
- `SIGNAL_SWING`
- `SIGNAL_CHART_RESPONSE`
- `SIGNAL_DISCOVERY`
- `RAG_ASSISTANT`
- `PAPER_TRADE`
- `LIVE_TRADE` (기본 OFF 고정)
- `AUTO_ORDER_WITH_ADMIN_APPROVAL`
- `AUTO_ORDER_FULLY_AUTOMATED` (기본 OFF 고정 + 별도 보호장치)

---

# J. 자동매매 확장 대비 구조 (이번 단계는 준비까지만)
## J-1. 주문 실행 단계 분리
- `ANALYZE` -> `RECOMMEND` -> `APPROVE` -> `ORDER_REQUESTED` -> `ORDER_EXECUTED`
- 기본은 `APPROVE` 수동 승인 필수
- 관리자 승인 API/화면만 구현하고 실주문은 OFF 유지 가능

## J-2. 감사/재현성
주문/추천 관련 모든 결정은 재현 가능해야 함:
- 입력 시세 스냅샷
- 연결 뉴스/이벤트
- 룰 히트
- 리스크 체크
- RAG 요약 결과
- 최종 승인자/시각
- trace_id

---

# K. API/응답 규격 개선 (실사용 화면용)
## K-1. 종목 요약 응답 (예시 필드)
- `asset_id`, `symbol`, `name`, `market`, `country`
- `price`, `change_amount`, `change_rate_pct`, `volume`
- `quote_time_utc`, `is_delayed`
- `strategy_type`, `action`
- `good_news_probability`, `bad_news_probability`, `uncertainty_score`
- `combined_confidence`
- `news_count_1h`, `news_count_24h`, `news_count_7d`
- `top_reasons[]`
- `risk_flags[]`
- `trace_id`

## K-2. 상세 응답은 사람이 이해 가능한 수준으로
기존 “호재확률 0.500 / 악재확률 0.500 / 결합신뢰 0.454” 수준으로 끝내지 말고,
반드시 아래를 포함:
- “왜 이런 값이 나왔는지”
- “무엇이 부족해서 HOLD/WATCH인지”
- “추가로 어떤 데이터가 들어오면 판단이 바뀌는지”
- “뉴스 근거/가격근거/리스크근거 분리”

---

# L. 테스트/검증/수용 기준 (강제)
## L-1. 기능 수용 기준
1. 국가/테마/전략 변경 시 동일 종목 반복 노출이 눈에 띄게 줄어든다.
2. 핵심분야(자원/방산/우주/AI/반도체/로봇/에너지) 선택 시 전략패널 결과에 실제 반영된다.
3. 종목 카드에 현재가/등락률/거래량/시각이 표시된다.
4. 상세보기에서 뉴스 근거(호재/악재 원인)와 리스크 차단 사유를 확인할 수 있다.
5. 확률값이 종목별/상황별로 차별화되며 50% 고정 수렴이 감소한다.
6. 데이터 부족 시 무의미한 확률 대신 “중립/데이터 부족” 상태로 표기된다.
7. 관리자 진단에서 수집 품질/매핑 품질/중복노출/전략 액션 분포를 확인할 수 있다.
8. 신규/변경 테이블/컬럼 코멘트가 DB에서 조회 가능하다.
9. 기존 뉴스 포털 홈 기능/자동갱신/번역 흐름이 정상 동작한다.

## L-2. 기술 검증
- compile/test 수행
- migration 적용 확인
- 로컬 seed + mock provider로 기본 동작 확인
- provider 장애/빈응답/지연 시 fallback 검증
- trace_id 기반 상세 조회로 원인 재현 가능 확인

---

# M. 작업 결과물 출력 형식 (Codex 응답 요구)
작업 완료 후 다음 형식으로 보고하라.

1. 변경 요약 (무엇을 왜 바꿨는지)
2. Git 작업 내역
   - 체크포인트 커밋 해시/메시지
   - 신규 브랜치명
   - 기능별 커밋 목록
3. DB 변경 내역
   - 신규/변경 테이블
   - 컬럼 추가/변경
   - TABLE/COLUMN COMMENT 적용 여부
4. API 변경 내역
   - 신규/변경 엔드포인트
   - 응답 필드 변경
5. UI 변경 내역
   - 홈/전략패널/상세/관리자진단 개선사항
6. 테스트/검증 결과
7. 남은 과제 (실주문 연동, 계좌/주문체결, 운영보안 등)
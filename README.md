# Global News Dashboard (GND)

공식 소스 기반 글로벌 뉴스 대시보드 프로젝트입니다.

## Stack

- Java 21 (로컬 표준) / Java 25(운영 확장 트랙)
- Spring Boot 3.x, MVC, Security, JPA, Quartz
- H2(local), PostgreSQL(운영), Redis(optional local)
- jQuery 4.0 SPA
- Docker / Nginx

## Modules

- `core`: Entity/Repository/Policy/DTO
- `collector`: condGET 수집, 정규화, 중복 제거, retry, DLQ
- `nlp`: 번역/요약 게이트
- `api`: REST API, OpenAPI, validation, seed/ingest admin
- `web`: 포털형 섹션 UI SPA
- `batch`: Quartz 잡(Fetch/NLP/Cleanup)

## 왜 0건이 나왔는가

초기 구현에서 로컬 프로필이 `in-memory H2`였고 시드/수집 트리거가 없어서 재시작 시 데이터가 비었습니다.

현재는 다음이 반영되었습니다.

- 로컬 DB: `jdbc:h2:file:./.data/gnd` (API/BATCH 공유)
- 로컬 소스 부트스트랩: 대상국 10개(KR/US/UK/DE/FR/JP/CN/IN/RU/BR) 무료 RSS 소스 자동 등록
- 로컬 샘플 기사 기본값: 비활성 (`app.seed.sample-news-enabled=false`)
- 수동 수집 API: `POST /api/admin/ingest/run`
- 수집기: RSS/Atom 기사 단위 파싱 저장(제목/링크/발행시각), 카테고리 휴리스틱 분류
- 카드 썸네일: 원문 이미지 추출 우선 + 도메인 favicon fallback
- 조회 언어: 기본 한국어(`view_lang=ko`) + 원문 토글(`view_lang=raw`)
- 보관 정책: 최대 7일 하드 보관 상한 + TTL 만료 정리

즉, 기본 화면에서 `0건`은 "수집 전 상태"로 정상일 수 있으며, 수동 수집 실행 후 실시간 기사 카드가 채워집니다.

## API

- `GET /api/news`
- `GET /api/news/{id}`
- `GET /api/insight`
- `GET /api/insight/stocks`
- `CRUD /api/admin/sources` (ADMIN)
- `POST /api/admin/ingest/run` (ADMIN)

OpenAPI: `api/src/main/resources/openapi.yaml`

### 조회 언어(한국어/원문) 파라미터

- 목록: `GET /api/news?...&view_lang=ko` (기본값)
- 원문: `GET /api/news?...&view_lang=raw`
- 상세: `GET /api/news/{id}?view_lang=ko|raw`

설명:
- `ko`: 한국어 우선 노출. 번역 미완료 시 원문 fallback 즉시 응답 후 백그라운드 번역 저장.
- `raw`: 원문 우선 노출.
- 번역 상태는 `meta.translation_pending`으로 확인 가능.

### 개인용 주식 시그널 API

- `GET /api/insight/stocks?country=KR&period=24h&limit=5`
- 응답: 종목코드/종목명/상승확률/하락확률/신뢰도/근거 요약
- 모델: `rule-heuristic-v1` (개인 참고용, 투자 자문 아님)

### 수동 수집 호출 예시

```powershell
$body = @{ limit=50 } | ConvertTo-Json
Invoke-RestMethod -Method POST `
  -Uri 'http://localhost:8080/api/admin/ingest/run' `
  -Headers @{ 'X-API-KEY'='change-me'; 'Content-Type'='application/json' } `
  -Body $body
```

유료 API 필요 여부:

- 기본 수집/표출은 **유료 API 없이** 무료 RSS 소스로 동작합니다.
- 더 빠른 속보성, 고품질 메타데이터(썸네일/원문요약/권리정책 보장)가 필요하면 유료 API를 추가로 붙이는 방식입니다.

### 소스 등록 예시(선택)

```powershell
$src = @{
  sid='rss-reuters-world-p0'
  country='US'
  sourceGrade='P0'
  priority=1
  allowFetch=$true
  allowStoreRaw=$true
  allowStoreDerived=$true
  cacheTtlSeconds=7200
  licensePolicy='rss-public'
  robotsPolicy='allow'
  endpointUrl='https://feeds.reuters.com/Reuters/worldNews'
} | ConvertTo-Json

Invoke-RestMethod -Method POST `
  -Uri 'http://localhost:8080/api/admin/sources' `
  -Headers @{ 'X-API-KEY'='change-me'; 'Content-Type'='application/json' } `
  -Body $src
```

## Local Run

1. API 실행

```powershell
cd "c:\Users\zxczx\Downloads\NAVER WORKS\project\news"
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path
$env:Path = "$env:JAVA_HOME\\bin;$env:Path"
.\gradlew.bat :api:bootRun --args="--spring.profiles.active=local --server.port=8080"
```

2. WEB 실행

```powershell
cd "c:\Users\zxczx\Downloads\NAVER WORKS\project\news"
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path
$env:Path = "$env:JAVA_HOME\\bin;$env:Path"
.\gradlew.bat :web:bootRun --args="--server.port=8081"
```

3. BATCH 실행(실시간 수집/정리 필수)

```powershell
cd "c:\Users\zxczx\Downloads\NAVER WORKS\project\news"
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path
$env:Path = "$env:JAVA_HOME\\bin;$env:Path"
.\gradlew.bat :batch:bootRun --args="--spring.profiles.active=local"
```

참고: BATCH는 `spring.main.web-application-type=none`으로 동작하므로 웹 포트를 점유하지 않습니다.

참고: 이미 실행 중인 `bootRun` 프로세스가 있으면 jar 잠금으로 실패할 수 있습니다. 이 경우 기존 Java 프로세스를 종료하고 다시 실행하세요.

접속 URL

- Web: `http://localhost:8081`
- API: `http://localhost:8080/api/news?country=KR&category=BRK&period=30d&page=1&view_lang=ko`
- Swagger: `http://localhost:8080/swagger-ui/index.html`

실시간 화면 확인 순서:

1. API + WEB 기동
2. BATCH 기동(1분 주기 수집/1시간 주기 정리)
3. `POST /api/admin/ingest/run` 호출
4. 웹 `갱신` 클릭 또는 페이지 새로고침

## 백그라운드 실행(권장)

### 1) 로컬 백그라운드(Windows PowerShell)

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-local-background.ps1
```

종료:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\stop-local-background.ps1
```

### 2) 서버/운영 백그라운드(Docker Compose)

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\server-up.ps1
```

종료:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\server-down.ps1
```

`docker-compose.yml`에는 `restart: unless-stopped`가 적용되어 재부팅 후에도 자동 재기동됩니다.

## Rocky Linux 서버 배포(실운영형 백그라운드)

검증 환경:
- OS: Rocky Linux 9.6
- 서버: `192.168.30.39`
- 배포 경로: `/home/was/gnd-news`
- 실행 방식: `systemd` 서비스 4개(`gnd-h2`, `gnd-api`, `gnd-web`, `gnd-batch`)

### 1) 서버 1회 준비

```bash
sudo dnf -y install java-21-openjdk java-21-openjdk-devel unzip
sudo alternatives --set java /usr/lib/jvm/java-21-openjdk-21.0.10.0.7-1.el9.x86_64/bin/java
sudo alternatives --set javac /usr/lib/jvm/java-21-openjdk-21.0.10.0.7-1.el9.x86_64/bin/javac
```

### 2) 소스 업로드 후 빌드

```bash
mkdir -p /home/was/gnd-news
unzip -o /home/was/gnd-news-src.zip -d /home/was/gnd-news
chmod -R u+rwX,go+rX /home/was/gnd-news
chmod +x /home/was/gnd-news/gradlew
cd /home/was/gnd-news
./gradlew :api:bootJar :web:bootJar :batch:bootJar -x test --no-daemon
```

### 3) systemd 등록/기동

서비스 파일:
- `/etc/systemd/system/gnd-h2.service`
- `/etc/systemd/system/gnd-api.service`
- `/etc/systemd/system/gnd-web.service`
- `/etc/systemd/system/gnd-batch.service`

반영:

```bash
cd /home/was/gnd-news
chmod +x scripts/linux/server-install-systemd.sh
./scripts/linux/server-install-systemd.sh /home/was/gnd-news 192.168.30.39
```

### 4) 방화벽 오픈(필요 시)

```bash
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --permanent --add-port=8081/tcp
sudo firewall-cmd --permanent --add-port=9092/tcp
sudo firewall-cmd --reload
```

접속:
- 웹: `http://<SERVER_IP>:8081`
- API: `http://<SERVER_IP>:8080/api/news?...`
- 관리자 수집: `POST http://<SERVER_IP>:8080/api/admin/ingest/run`
- H2 TCP(DBeaver): `jdbc:h2:tcp://<SERVER_IP>:9092//home/was/gnd-news/.data/gnd;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`

## 서버 패치(업데이트) 방법

기본 방식은 "소스 재업로드 -> 재빌드 -> 서비스 재시작"입니다.

```bash
cd /home/was/gnd-news
unzip -o /home/was/gnd-news-src.zip -d /home/was/gnd-news
chmod -R u+rwX,go+rX /home/was/gnd-news
chmod +x /home/was/gnd-news/gradlew
chmod +x scripts/linux/server-build-restart.sh scripts/linux/server-unpack-and-deploy.sh
./scripts/linux/server-unpack-and-deploy.sh /home/was/gnd-news-src.zip /home/was/gnd-news
```

로그 확인:

```bash
sudo journalctl -u gnd-api -n 200 --no-pager
sudo journalctl -u gnd-web -n 200 --no-pager
sudo journalctl -u gnd-batch -n 200 --no-pager
sudo journalctl -u gnd-h2 -n 200 --no-pager
```

## DBeaver 접속(H2 상시 원격조회)

- Driver: `H2`
- Username: `sa`
- Password: 공란
- JDBC URL:

```text
jdbc:h2:tcp://192.168.30.39:9092//home/was/gnd-news/.data/gnd;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH
```

주의:
- `HSQLDB` 드라이버가 아니라 `H2` 드라이버를 사용해야 합니다.
- 기존 파일 잠금 충돌 방지를 위해 API/BATCH는 `gnd-h2` TCP 서버를 통해 DB에 접속하도록 `systemd` 환경변수(`SPRING_DATASOURCE_URL`)로 강제합니다.
- 과거에 수동으로 실행한 `org.h2.tools.Server` 프로세스가 9092를 점유 중이면 `gnd-h2.service`가 기동 실패할 수 있으므로, 중복 H2 프로세스를 종료한 뒤 `sudo systemctl restart gnd-h2`를 실행합니다.

## 보관 정책(7일)

- 하드 보관 상한: `app.news.max-retention-days=7`
- 수집 시 TTL: 최대 7일로 자동 보정
- 정리 잡: 1시간마다 실행, 7일 초과 데이터 즉시 삭제 + TTL 만료 데이터 정리

## 한국어 표시 정책

- 기본 화면은 한국어 보기(`view_lang=ko`)
- 필요 시 원문 보기(`view_lang=raw`)로 전환
- 웹 필터에 `한국어 보기 / 원문 보기` 토글 제공
- 번역 미완료 시 즉시 원문 fallback 후 비동기 번역 처리
- 자동 번역 실패 시 원문 fallback 유지

## 심화 기능(PROMPT-5)

- 요청 경합 제어: 이전 요청 abort + 최신 요청만 반영(stale render 방지)
- 실시간 비동기 갱신: 기본 30초 주기로 뉴스/시그널 자동 갱신
- 최신순 정렬 고정: `pub_utc DESC -> fetch_utc DESC -> created_at DESC`
- 썸네일 가독성 강화: 원본 이미지 우선, 실패 시 제목 기반 SVG 썸네일 fallback

## build.gradle 빨간 밑줄(IDE) 대응

워크스페이스 설정을 적용했습니다: `.vscode/settings.json`

- `java.import.gradle.wrapper.enabled=true`
- `java.import.gradle.java.home=${workspaceFolder}\\.tools\\jdk-21`
- `java.jdt.ls.java.home=${workspaceFolder}\\.tools\\jdk-21`
- `java.configuration.updateBuildConfiguration=automatic`

추가 권장:

1. VS Code: `Java: Clean Java Language Server Workspace`
2. VS Code 재시작
3. 필요 시 시스템 JAVA_HOME을 JDK 21로 전환

## Docs

- 최종 산출 문서: `docs/final-deliverables.md`
- 작업 타임라인: `work_timeline.md`

# core-api 서비스 구현 문서

> error-archive 모노레포의 **에러 케이스 도메인 서비스**. 사용자가 마주친 에러를 기록·정리·재활용하기 위한 핵심 비즈니스 로직을 담는다. 본 문서는 무엇을 구현했고, 어떻게 동작하며, 어떤 API 를 어떻게 호출하는지 한눈에 보이도록 정리한다.

작성 시점: 2026-05-11 (게이트웨이 + iam-api 통합, CircuitBreaker 적용 완료)

---

## 0. TL;DR

| | 값 |
|--|-----|
| 위치 | `error-archive/core-api/` (composite-build sibling) |
| 포트 | 8081 (내부 전용 — 외부 직접 노출 X, 게이트웨이 8000 만 외부) |
| 베이스 | Spring Boot 4.0.4 + Kotlin 2.3.20 + JVM 25 |
| 아키텍처 | **헥사고날** (domain / application / infrastructure / presentation) |
| 영속 | Spring Data JPA + PostgreSQL (`core_db`, port 5433) |
| 외부 호출 | iam-api workspace 검증 (RestClient + CircuitBreaker) |
| 인증 | **없음** — 게이트웨이가 검증, `X-User-Id` 헤더만 신뢰 |
| 회복성 | Resilience4j CircuitBreaker (iam-api 호출 보호) |
| 구현 완료 | ErrorCase 생성, Attachment 업로드 |
| 미구현 | 조회 / 수정 / 삭제 / 검색 |

---

## 1. core-api 의 책임

### 하는 일
- ErrorCase 도메인 모델 + 영속 (PostgreSQL)
- 사용자가 붙여넣은 stack trace 를 파싱하여 **exception class / message / stacktrace / fingerprint** 추출
- 첨부 파일 업로드 (로컬 FS, 운영 S3 예정)
- 코드 스니펫 인라인 첨부 + marker 발급 (`@snippet(abc12345)` / `@attach(deadbeef)` 형식)
- iam-api 호출로 워크스페이스 권한 검증
- CircuitBreaker 로 외부 호출 격리

### 하지 않는 일
- ❌ **인증 (JWT 검증)** — gateway 단독 책임. core-api 는 `X-User-Id` 헤더만 신뢰
- ❌ 토큰 발급 / OAuth — iam-api 책임
- ❌ 사용자 / 워크스페이스 도메인 — iam-api 책임
- ❌ 이메일 / 알림 — noti-api 책임 (예정)
- ❌ 검색 / 분석 — insight-api 책임 (예정)

### 게이트웨이 패턴의 결과
core-api 의 `build.gradle.kts` 에는 **Spring Security 의존성이 단 한 줄도 없음**:
```kotlin
// 의도적으로 없음
// implementation("org.springframework.boot:spring-boot-starter-security")
// implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
```
컨트롤러는 `@RequestHeader("X-User-Id") userId: Long` 한 줄로 사용자 식별 끝.

---

## 2. 기술 스택

| 영역 | 선택 |
|------|-----|
| Web | Spring WebMVC (servlet) |
| Persistence | Spring Data JPA + Hibernate 6 + PostgreSQL 16 |
| Domain | Kotlin Data Class + VO (value class) + 헥사고날 port/adapter |
| 외부 HTTP | Spring `RestClient` + token relay interceptor |
| 회복성 | Resilience4j (CircuitBreaker + TimeLimiter) |
| 로깅 | kotlin-logging-jvm (lazy KLogger) |
| 추적 | micrometer-tracing-bridge-brave |
| Spring Cloud | `2025.1.1` (Boot 4.0 매칭) |

---

## 3. 디렉터리 구조 (헥사고날)

```
core-api/
├── build.gradle.kts                     Spring Cloud BOM + JPA + circuitbreaker
├── docker-compose.yml                   PostgreSQL :5433 (iam-api 의 5432 와 분리)
├── settings.gradle.kts
├── docs/
│   └── core-api-implementation.md       (본 문서)
└── src/main/
    ├── kotlin/org/studieojavry/coreapi/
    │   ├── CoreApiApplication.kt
    │   ├── shared/
    │   │   ├── config/
    │   │   │   ├── CoreApiConfigRegistration.kt   @ConfigurationPropertiesScan
    │   │   │   └── ResilienceConfig.kt            CB 기본 정책
    │   │   └── util/
    │   │       ├── FingerprintGenerator.kt        SHA-256 fingerprint
    │   │       └── HashUtils.kt
    │   └── errorcase/                              ← 도메인 bounded context
    │       ├── domain/                             [도메인 레이어 — 프레임워크 의존 X]
    │       │   └── model/
    │       │       ├── ErrorCase.kt                aggregate root
    │       │       ├── AttachmentKind.kt           IMAGE / JSON / TEXT / DOCUMENT / OTHER
    │       │       └── vo/                         value objects
    │       │           ├── Attachment.kt
    │       │           ├── CodeSnippet.kt
    │       │           ├── ErrorCaseStatus.kt      DRAFT / OPEN / IN_PROGRESS / RESOLVED / CLOSED
    │       │           ├── ErrorSnapshot.kt        paste 파싱 결과
    │       │           ├── Fingerprint.kt          SHA-256 (중복 검출용)
    │       │           ├── Meta.kt                 workspaceId / severity / environment
    │       │           ├── RawStackTrace.kt        정규화 가능한 stack trace
    │       │           └── Severity.kt             S1 ~ S4
    │       ├── application/                        [애플리케이션 레이어 — port 정의]
    │       │   ├── command/
    │       │   │   ├── CreateErrorCaseCommand.kt
    │       │   │   └── CreateAttachmentCommand.kt
    │       │   ├── port/                           outbound 인터페이스
    │       │   │   ├── ErrorCaseRepositoryPort.kt
    │       │   │   ├── ErrorCaseAttachmentRepositoryPort.kt
    │       │   │   ├── AttachmentStoragePort.kt
    │       │   │   ├── MarkerIdGeneratorPort.kt
    │       │   │   ├── ErrorSnaphostExtractorPort.kt
    │       │   │   └── WorkspaceQueryPort.kt
    │       │   └── usecase/
    │       │       ├── CreateErrorCaseUseCase.kt
    │       │       └── CreateAttachmentUseCase.kt
    │       ├── infrastructure/                     [어댑터 레이어 — 외부 시스템 연결]
    │       │   ├── jpa/
    │       │   │   ├── entity/
    │       │   │   │   ├── ErrorCaseEntity.kt
    │       │   │   │   ├── CodeSnippetEntity.kt
    │       │   │   │   ├── AttachmentEntity.kt
    │       │   │   │   ├── ErrorSnapshotEmbeddable.kt
    │       │   │   │   └── MetaEmbeddable.kt
    │       │   │   ├── ErrorCaseJpaRepository.kt
    │       │   │   ├── CodeSnippetJpaRepository.kt
    │       │   │   ├── AttachmentJpaRepository.kt
    │       │   │   └── adapter/
    │       │   │       ├── ErrorCaseRepositoryAdapter.kt
    │       │   │       └── AttachmentRepositoryAdapter.kt
    │       │   ├── extractor/
    │       │   │   └── RegexErrorSnapshotExtractorAdapter.kt    paste → 구조화
    │       │   ├── marker/
    │       │   │   └── RandomMarkerIdGeneratorAdapter.kt        8-char hex
    │       │   ├── storage/
    │       │   │   └── LocalFileSystemAttachmentStorageAdapter.kt
    │       │   └── iam/
    │       │       ├── IamApiRestClientConfig.kt                token relay interceptor
    │       │       └── IamWorkspaceQueryAdapter.kt              CB 적용
    │       ├── config/                              ConfigurationProperties
    │       │   ├── AttachmentStorageProperties.kt
    │       │   └── IamApiProperties.kt
    │       └── presentation/                       [HTTP 레이어]
    │           └── web/
    │               ├── ErrorCaseController.kt
    │               ├── ErrorCaseAttachmentController.kt
    │               └── dto/
    │                   ├── request/
    │                   │   ├── CreateErrorCaseRequest.kt
    │                   │   ├── CodeSnippetCreateRequest.kt
    │                   │   ├── AttachmentUploadRequest.kt
    │                   │   └── UpdateErrorCaseRequest.kt    (DTO 만 — controller 미구현)
    │                   └── response/
    │                       ├── CreateErrorCaseResponse.kt
    │                       ├── AttachmentUploadResponse.kt
    │                       └── CodeSnippetCreateResponse.kt
    └── resources/
        ├── application.yml                          공통
        └── application-local.yml                    profile 별
```

### 헥사고날 의존 방향
```
domain  ← application  ← infrastructure
                      ← presentation
```
- **domain** 은 어떤 것도 의존하지 않음 (순수 Kotlin)
- **application** 은 domain 만 의존, port 인터페이스 정의
- **infrastructure** 가 port 를 구현 (JPA / RestClient / FS)
- **presentation** 이 application 호출

---

## 4. 도메인 모델

### 4.1 ErrorCase (aggregate root)
```kotlin
class ErrorCase private constructor(
    val id: Long?,
    val ownerUserId: Long,                  // ← 게이트웨이가 주입한 X-User-Id
    var title: String,                       // 최대 200자, 필수
    var scope: String?,                      // 예: "core-api / order"
    var snapshot: ErrorSnapshot?,            // paste 파싱 결과 (없을 수 있음)
    var description: String?,                // markdown 본문 (@snippet/@attach marker 포함)
    var meta: Meta,                          // workspaceId / severity / environment
    val snippets: MutableList<CodeSnippet>,  // 코드 스니펫 자식
    val attachments: MutableList<Attachment>, // 첨부 파일 자식
    val status: ErrorCaseStatus,             // 항상 OPEN 으로 생성
    val occurredAt: LocalDateTime?,          // 에러가 실제로 발생한 시각
    val createdAt: LocalDateTime,            // 케이스가 기록된 시각
    var updatedAt: LocalDateTime
)
```
- **private constructor + companion `create()`** — 직접 생성 금지, 항상 팩토리 경유
- `reconstitute()` 는 JPA 어댑터가 DB row 로부터 복원할 때 사용

### 4.2 ErrorSnapshot — paste 파싱 결과
```kotlin
class ErrorSnapshot(
    val rawPaste: String?,
    val exceptionClass: String?,         // "java.net.SocketTimeoutException"
    val exceptionMessage: String?,
    val rawStackTrace: RawStackTrace?,
    val fingeprint: Fingerprint?         // SHA-256 hash for 중복 검출
)
```
- `RegexErrorSnapshotExtractorAdapter` 가 휴리스틱으로 추출
- `Fingerprint` = SHA-256(exceptionClass + 정규화된 stacktrace) → 같은 에러를 reliably 묶음

### 4.3 CodeSnippet — 코드 첨부
```kotlin
class CodeSnippet(
    val markerId: String,                // 8-char hex, "@snippet(...)" 로 본문 임베드
    val title: String,
    val language: String,                // java/kotlin/sql/yaml/json/bash/text
    val filePathOrClass: String?,
    val lineRange: String?,              // "L120-L186"
    val caption: String?,
    val code: String
)
```

### 4.4 Attachment — 파일 첨부
```kotlin
class Attachment(
    val markerId: String,                // 8-char hex, "@attach(...)" 로 본문 임베드
    val title: String?,
    val caption: String?,
    val fileName: String,
    val contentType: String,
    val size: Long,
    val kind: AttachmentKind,            // IMAGE / JSON / TEXT / DOCUMENT / OTHER
    val storageUrl: String,              // 저장소 공개 URL
    val previewText: String?             // JSON/TEXT 의 경우 앞 2000자 미리보기
)

fun embedToken(): String = "@attach($markerId)"
```

### 4.5 Severity / ErrorCaseStatus
```kotlin
enum class Severity(val code: Int, val label: String) {
    S1(1, "Outage"), S2(2, "Degraded"), S3(3, "Minor"), S4(4, "Info");
}

enum class ErrorCaseStatus {
    DRAFT, OPEN, IN_PROGRESS, RESOLVED, CLOSED
}
```

---

## 5. API 엔드포인트

> 모든 호출은 **게이트웨이(8000) 통해서** 들어와야 함. core-api(8081) 직접 호출은 운영에서 차단.

### 5.1 POST `/api/v1/error-cases` — 에러 케이스 생성

**Headers**:
- `Authorization: Bearer <JWT>` (게이트웨이가 검증 후 X-User-Id 헤더로 변환)

**Body** (application/json):
```json
{
  "title": "[prod] 주문서 API 타임아웃",
  "scope": "core-api / order",
  "paste": "java.net.SocketTimeoutException: ...\n\tat com.foo.Bar...",
  "description": "# 문제\n@snippet(de4f5678) 트랜잭션 길어\n@attach(abc12345)",
  "snippets": [
    {
      "title": "OrderService", "language": "java",
      "filePathOrClass": "com.foo.OrderService",
      "lineRange": "L120-L186",
      "caption": "...",
      "code": "..."
    }
  ],
  "attachmentMarkerIds": ["abc12345"],
  "workspaceId": 1,
  "severity": 2,
  "environment": "k8s / cloud-sql / ap-northeast-2",
  "occurredAt": "2026-05-11T18:10:00"
}
```

**검증**:
- `title` (NotBlank, ≤200자), `scope` (NotBlank), `paste` (NotBlank) — 필수 3개
- `snippets` 의 각 항목: `language` / `code` (NotBlank)
- `workspaceId` 가 있으면 iam-api 호출하여 사용자가 해당 워크스페이스 멤버인지 검증
- `attachmentMarkerIds` 의 각 marker 가 DB 에 존재하는지 검증

**응답** (201 CREATED):
```json
{
  "id": 17,
  "title": "[prod] 주문서 API 타임아웃",
  "status": "OPEN",
  "fingerprint": "9af3e1c8...",
  "snippetMarkerIds": ["de4f5678"],
  "attachmentMarkerIds": ["abc12345"],
  "createdAt": "2026-05-11T18:11:23"
}
```

### 5.2 POST `/api/v1/error-attachments` — 첨부 업로드

**Headers**:
- `Authorization: Bearer <JWT>` (게이트웨이 검증)
- `Content-Type: multipart/form-data`

**Form parts**:
| 파트 | 필수 | 설명 |
|------|------|-----|
| `file` | ✅ | 업로드할 파일 (이미지/JSON/text/PDF/etc.) |
| `title` | | 사람이 보는 제목 |
| `caption` | | 한 줄 설명 |

**응답** (201 CREATED):
```json
{
  "markerId": "abc12345",
  "embedToken": "@attach(abc12345)",
  "fileName": "screenshot.png",
  "contentType": "image/png",
  "size": 42789,
  "kind": "IMAGE",
  "storageUrl": "http://localhost:8081/files/ab/abc12345__screenshot.png"
}
```

**워크플로우**:
1. 사용자가 첨부 모달에서 파일 선택 → 이 API 호출 → `markerId` 받음
2. 본문에 `@attach(abc12345)` 마커 삽입
3. 케이스 생성 시 `attachmentMarkerIds: ["abc12345"]` 로 참조

---

## 6. 핵심 Use Case — `CreateErrorCaseUseCase` 흐름

```
@Transactional
fun invoke(command: CreateErrorCaseCommand): Result {
    ① command.workspaceId 가 있으면
       → workspaceQuery.existsWorkspaceForUser(userId, workspaceId)
         → IamWorkspaceQueryAdapter.existsWorkspaceForUser
           → cb.run { iamApi.get("/api/v1/workspaces/{id}") }    ← CircuitBreaker
       → false 면 IllegalArgumentException

    ② command.attachmentMarkerIds 가 있으면
       → attachmentRepository.findAllByMarkerIds(markerIds)
       → 누락된 marker 가 있으면 IllegalArgumentException

    ③ command.snippets 각각에 markerId 발급
       → markerIdGenerator.generateSnippetMarkerId()  (8-char hex)

    ④ command.paste 파싱
       → errorSnapshotExtractor.extract(paste)
         → exceptionClass / exceptionMessage / rawStackTrace 추출 (휴리스틱)
       → FingerprintGenerator.generate(exClass, rawStackTrace)
         → SHA-256(exClass + " | " + normalized_stack)

    ⑤ Meta.create(workspaceId, severityCode, environment)

    ⑥ ErrorCase.create(ownerUserId, title, scope, snapshot, ...)
       → 도메인 invariants 검증 (title 200자, 공백 X)

    ⑦ errorCaseRepository.save(errorCase)
       → ErrorCaseRepositoryAdapter
         → INSERT error_case
         → INSERT error_case_snippet (각각)
         → UPDATE error_case_attachment SET error_case_id = ?  (기존 attachment 연결)

    ⑧ Result(id, title, status, fingerprint, markerIds, createdAt) 반환
}
```

---

## 7. 외부 시스템 통합

### 7.1 iam-api 호출 — `IamApiRestClientConfig` + `IamWorkspaceQueryAdapter`

```kotlin
@Bean
fun iamApiRestClient(properties: IamApiProperties): RestClient =
    RestClient.builder()
        .baseUrl(properties.baseUrl)            // local: http://localhost:8080
        .requestInterceptor { request, body, execution ->
            // token relay: 현재 요청의 헤더를 그대로 forward
            currentRequest()?.let { incoming ->
                incoming.getHeader("X-User-Id")?.let { request.headers.setIfAbsent("X-User-Id", it) }
                incoming.getHeader("Authorization")?.let { request.headers.setIfAbsent("Authorization", it) }
            }
            execution.execute(request, body)
        }
        .build()
```

**token relay 패턴**: core-api 자체는 JWT 를 모르지만, 현재 사용자 요청의 `Authorization` 헤더와 게이트웨이가 주입한 `X-User-Id` 를 그대로 iam-api 에 전달. iam-api 가 자체 JWT 검증으로 한 번 더 확인 (defense-in-depth).

**CircuitBreaker 적용** (`IamWorkspaceQueryAdapter`):
```kotlin
private val cb = cbFactory.create("iamApi")

override fun existsWorkspaceForUser(userId: Long, workspaceId: Long): Boolean = cb.run(
    { /* iam-api 호출 */ },
    { throwable -> throw IamApiUnavailableException("workspace check unavailable", throwable) }
)

override fun getAvailableWorkspaces(userId: Long): List<WorkspaceSummary> = cb.run(
    { /* iam-api 호출 */ },
    { throwable -> emptyList() }    // ← degraded UX (UI 가 빈 목록 표시)
)
```

CircuitBreaker 정책 (`shared/config/ResilienceConfig.kt`):
- 슬라이딩 윈도우 20건, 실패율 50% 시 OPEN
- OPEN 30초 → HALF_OPEN, 5건 시도
- 모든 호출 5초 timeout
- `minimumNumberOfCalls(10)` — 적은 샘플로 오판 방지

**4xx 와 5xx 처리 구분**:
- 403/404 (권한 / 없음) → 비즈니스 결과, CB 카운트 X, `false` 반환
- 5xx → CB 가 실패로 카운트, OPEN 으로 갈 수 있음

### 7.2 첨부 저장소 — `LocalFileSystemAttachmentStorageAdapter`

```kotlin
fun store(markerId, fileName, bytes, contentType): StoredAttachment {
    val shard = markerId.take(2)                                       // ab
    val safeName = "${markerId}__${fileName.replace(/[^A-Za-z0-9._-]/, '_')}"
    Path.of(storagePath, shard, safeName).also { Files.write(it, bytes) }
    val publicUrl = "$publicBaseUrl/$shard/$safeName"
    return StoredAttachment(publicUrl)
}
```
- 로컬 디스크: `./var/attachments/<2-char shard>/<markerId>__<safe-filename>`
- 공개 URL: `http://localhost:8081/files/<shard>/<...>` (정적 서빙 별도 설정 필요 — 현재 placeholder)
- 운영: S3 / GCS 어댑터로 교체 예정

---

## 8. DB 스키마 요약

### `error_case`
```
id                          BIGSERIAL PK
owner_user_id               BIGINT NOT NULL
title                       VARCHAR(200) NOT NULL
scope                       VARCHAR(200)
description                 TEXT
status                      VARCHAR(20) NOT NULL    (DRAFT/OPEN/...)
occurred_at                 TIMESTAMP
created_at                  TIMESTAMP NOT NULL
updated_at                  TIMESTAMP NOT NULL

-- Embedded snapshot
snapshot_raw_paste          TEXT
snapshot_exception_class    VARCHAR(500)
snapshot_exception_message  TEXT
snapshot_raw_stacktrace     TEXT
snapshot_fingerprint        VARCHAR(128)            (인덱스)

-- Embedded meta
meta_workspace_id           BIGINT                  (인덱스)
meta_severity               INT
meta_environment            VARCHAR(200)
```

### `error_case_snippet`
```
id                          BIGSERIAL PK
error_case_id               BIGINT                  (인덱스)
marker_id                   VARCHAR(32) UNIQUE      (인덱스)
title                       VARCHAR(200) NOT NULL
language                    VARCHAR(30) NOT NULL
file_path_or_class          VARCHAR(500)
line_range                  VARCHAR(50)
caption                     TEXT
code                        TEXT NOT NULL
```

### `error_case_attachment`
```
id                          BIGSERIAL PK
error_case_id               BIGINT                  (인덱스, NULL 가능 — 케이스 연결 전)
marker_id                   VARCHAR(32) UNIQUE      (인덱스)
title                       VARCHAR(200)
caption                     TEXT
file_name                   VARCHAR(500) NOT NULL
content_type                VARCHAR(200) NOT NULL
size                        BIGINT NOT NULL
kind                        VARCHAR(20) NOT NULL    (IMAGE/JSON/TEXT/DOCUMENT/OTHER)
storage_url                 VARCHAR(1000) NOT NULL
preview_text                TEXT
```

ddl-auto: `update` (local), `validate` (stg/prod). 운영에서는 Flyway 마이그레이션 필요.

---

## 9. 운영 / 실행

### 9.1 로컬 부팅 (3단계)
```bash
# 1) PostgreSQL 띄우기
cd core-api
docker compose up -d
# → core-api-postgres :5433 에서 동작

# 2) core-api 부트
./gradlew bootRun --args='--spring.profiles.active=local'
# → 8081

# 3) (선택) gateway 도 띄워서 외부 진입점 확보
cd ../gateway
./gradlew bootRun --args='--spring.profiles.active=local'
# → 8000
```

### 9.2 헬스체크
```bash
# gateway 통해 (정상 흐름)
curl http://localhost:8000/actuator/health

# core-api 직접 (디버깅용, 운영에선 차단)
curl http://localhost:8081/actuator/health
```

### 9.3 로그 레벨 (local)
```yaml
logging:
  level:
    org.studieojavry: DEBUG       # 도메인 / 어댑터 로그
    org.hibernate.SQL: DEBUG      # SQL 출력
```

### 9.4 환경별 yml 차이
| 항목 | local | dev/stg/prod |
|------|-------|--------------|
| 포트 | 8081 | 환경 따라 (LB 뒤) |
| DB | docker-compose PG :5433 | RDS env 주입 |
| ddl-auto | update | validate (Flyway 가 마이그) |
| iam-api base-url | http://localhost:8080 | https://iam.{env}.studieo-javry.com |
| attachment storage | 로컬 FS | S3 어댑터 (미구현) |

---

## 10. 설정 / 환경 변수

### 10.1 `application.yml` (공통)
```yaml
spring:
  application: { name: core-api }
  jpa:
    open-in-view: false
    properties:
      hibernate.dialect: PostgreSQLDialect
  servlet.multipart:
    max-file-size: 25MB

core:
  attachment:
    storage-path: ${CORE_ATTACHMENT_STORAGE_PATH:./var/attachments}
    public-base-url: ${CORE_ATTACHMENT_PUBLIC_BASE_URL:http://localhost:8081/files}
  iam-api:
    base-url: ${CORE_IAM_API_BASE_URL:http://localhost:8080}
```

### 10.2 `application-local.yml` (개발자 머신)
```yaml
server: { port: 8081 }
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/core_db
    username: core
    password: core_local_pw
```

---

## 11. CircuitBreaker 동작 요약

| 호출자 | 호출 대상 | CB 이름 | Fallback |
|-------|----------|--------|----------|
| core-api | iam-api `/workspaces/{id}` | `iamApi` | `IamApiUnavailableException` throw |
| core-api | iam-api `/workspaces` (목록) | `iamApi` | `emptyList()` (degraded UX) |

상태 확인:
```bash
curl http://localhost:8081/actuator/metrics/resilience4j.circuitbreaker.state \
  | jq '.availableTags'
```
> 단 `/actuator/**` 가 SecurityConfig 에서 permitAll 이어야 보임. core-api 는 자체 Security 가 없으므로 기본 허용.

---

## 12. 알려진 한계 / TODO

### 12.1 현재 미구현 — 다음 작업 후보
- [ ] GET / 조회 endpoints (단건 / 목록 / 검색)
- [ ] PATCH `/error-cases/{id}` (수정 + `ownerUserId == X-User-Id` 인가 체크)
- [ ] DELETE `/error-cases/{id}` (소프트 삭제 + 인가 체크)
- [ ] 첨부 파일 다운로드 endpoint (`storageUrl` 직접 서빙)
- [ ] iam-api JWKS 가 도입되면 변화 없음 — core-api 는 그대로 X-User-Id 신뢰

### 12.2 인가 검증 (Update / Delete 구현 시 필수)
도메인 모델에 `ownerUserId` 가 있으므로 다음 같은 패턴으로 검증:
```kotlin
@PatchMapping("/{id}")
fun update(@RequestHeader("X-User-Id") userId: Long, @PathVariable id: Long, ...) {
    val errorCase = repo.findById(id)
    require(errorCase.ownerUserId == userId) {
        throw ForbiddenException("not the owner")
    }
    // ...
}
```

### 12.3 운영 전환 시 필요한 작업
- 첨부 저장소: 로컬 FS → S3/GCS 어댑터 신규 구현 + `@Profile("dev|stg|prod")`
- DB 마이그레이션: ddl-auto 끄고 Flyway 도입
- `LocalFileSystemAttachmentStorageAdapter` 의 정적 파일 서빙 — 현재 placeholder URL 만 반환, 실제 다운로드 endpoint 없음
- iam-api 가 다운됐을 때의 비-CB 시나리오 (예: 워크스페이스 캐시) 검토

### 12.4 RS256 + JWKS 도입 시
core-api 는 **영향 없음**. 게이트웨이 패턴 덕분에 검증 방식이 바뀌어도 core-api 는 `X-User-Id` 만 보면 됨.

---

## 13. 핵심 코드 레퍼런스 (어디를 보면 되는가)

| 책임 | 코드 위치 |
|------|----------|
| API 진입점 — 에러 케이스 생성 | `errorcase/presentation/web/ErrorCaseController.kt` |
| API 진입점 — 첨부 업로드 | `errorcase/presentation/web/ErrorCaseAttachmentController.kt` |
| 비즈니스 로직 — 생성 흐름 | `errorcase/application/usecase/CreateErrorCaseUseCase.kt` |
| 비즈니스 로직 — 첨부 저장 | `errorcase/application/usecase/CreateAttachmentUseCase.kt` |
| 도메인 모델 | `errorcase/domain/model/ErrorCase.kt` 및 `vo/*` |
| paste 파싱 | `errorcase/infrastructure/extractor/RegexErrorSnapshotExtractorAdapter.kt` |
| fingerprint 산정 | `shared/util/FingerprintGenerator.kt` |
| iam-api 호출 + CB | `errorcase/infrastructure/iam/IamWorkspaceQueryAdapter.kt` |
| token relay 설정 | `errorcase/infrastructure/iam/IamApiRestClientConfig.kt` |
| 첨부 저장 | `errorcase/infrastructure/storage/LocalFileSystemAttachmentStorageAdapter.kt` |
| marker 발급 | `errorcase/infrastructure/marker/RandomMarkerIdGeneratorAdapter.kt` |
| JPA 매핑 | `errorcase/infrastructure/jpa/entity/*` + `adapter/*` |
| CB 정책 | `shared/config/ResilienceConfig.kt` |
| ConfigurationProperties 스캔 | `shared/config/CoreApiConfigRegistration.kt` |

---

## 14. 한 줄 요약

> core-api 는 **에러 케이스 도메인의 single source of truth**. 게이트웨이가 검증한 `X-User-Id` 만 신뢰하고 자체 인증 코드는 0줄. ErrorCase 생성 (paste → fingerprint 자동 추출, 첨부 marker 연결) + 첨부 업로드 + iam-api workspace 권한 검증(CircuitBreaker 적용) 까지 구현되어 있으며, 조회/수정/삭제는 다음 작업 대상.

---

## 15. 빠른 사용 예시 — End-to-end

```bash
# 0) 사전: gateway / core-api / iam-api / PostgreSQL 모두 띄움

# 1) (테스트용) JWT 발급 — local 프로필 secret 으로 직접 서명
TOKEN=$(python3 -c "
import base64, hmac, hashlib, json, time
s = 'local-dev-secret-local-dev-secret-local-dev-secret-local-dev-secret-local-dev'
n = int(time.time())
p = json.dumps({'iss':'https://iam.local','aud':'local-clients','sub':'42','typ':'access',
                'iat':n,'nbf':n,'exp':n+900,'roles':['ROLE_USER']}, separators=(',',':'))
h = json.dumps({'alg':'HS256','typ':'JWT'}, separators=(',',':'))
b = lambda x: base64.urlsafe_b64encode(x.encode()).rstrip(b'=').decode()
si = f'{b(h)}.{b(p)}'
sig = base64.urlsafe_b64encode(hmac.new(s.encode(), si.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()
print(f'{si}.{sig}')
")

# 2) 첨부 업로드
ATTACH=$(curl -s -X POST http://localhost:8000/api/v1/error-attachments \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/screenshot.png" \
  -F "title=실패 화면")
MARKER=$(echo "$ATTACH" | jq -r '.markerId')

# 3) 에러 케이스 생성
curl -s -X POST http://localhost:8000/api/v1/error-cases \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"title\": \"[prod] 주문서 API 타임아웃\",
    \"scope\": \"core-api / order\",
    \"paste\": \"java.net.SocketTimeoutException: Read timed out\",
    \"description\": \"# 문제\\n@attach($MARKER)\",
    \"snippets\": [],
    \"attachmentMarkerIds\": [\"$MARKER\"],
    \"severity\": 2,
    \"environment\": \"k8s / ap-northeast-2\"
  }" | jq .
```

# Client → Server 입력 필드 종합표

> 최종 갱신: 2026-06-07
> core-api 의 모든 HTTP endpoint 가 클라이언트에서 받는 **모든 입력 필드** 를 *위치(path/query/body/multipart)*, *타입*, *필수 여부*, *null 허용*, *검증 제약*, *예시* 로 명시.
> Swagger UI(`/swagger-ui.html`) 와 동등 정보 — 단 한 페이지로 *한 눈에 비교* 할 수 있도록.

**최근 변경 (2026-06-06 ~ 2026-06-07)**:
- ErrorCase: `scope` → `project` rename, `paste`·`project` nullable 화
- ErrorCase: `environment` 단일 필드 → `tags: List<String>` 자유 태그로 일반화 + 단건 추가/삭제 endpoint 2 신설 (1.6 / 1.7)
- Step: `status` enum 값 `SUCCESS/PARTIAL/FAILURE` → `RESOLVED/IN_PROGRESS/FAILED`
- Step: `attemptType` enum → **자유 String** (≤64). system 7개는 모두에게 기본 제공, 본인 커스텀은 자동 등록 + 별도 카탈로그 endpoint 3 신설 (§7)
- Step PATCH 응답: `UpdateStepResponse{step, caseStatus, suggestResolve}` 로 확장 (입력 필드는 변동 없음)

---

## 0. TL;DR · 표기법

- **7 controller · 31 endpoint** 정리 (error-cases 7 / error-snippets 3 / error-attachments 3 / steps 4 / solutions 3 / comments 8 / step-attempt-types 3)
- **공통 인증**: 모든 endpoint 가 `X-Internal-Auth` 헤더(internal JWT) 필요. principal=`userId: Long` 은 토큰의 `sub` claim 에서 추출 — 클라이언트가 *몸체로 보내는 필드 아님* (표에서 제외)
- **표기**:

| 기호 | 의미 |
|---|---|
| **필수 ✓** | `@field:NotBlank` / `@field:NotNull` / 코틀린 non-nullable + `Schema.RequiredMode.REQUIRED` 또는 path/multipart 필수 |
| **필수 ✗** | 코틀린 nullable(`?`) 또는 `default value` 보유 — 미포함 시 서버가 *유지* 또는 *기본값* 적용 |
| **null OK** | 코틀린 `?` 로 `null` 값 허용 (필드 자체는 보내야 하지만 값은 null 가능) |
| `≤N` | `@field:Size(max=N)` |
| `≥N` | `@field:Size(min=N)` 또는 `@field:Min(N)` |
| `1..4` | `@field:Min(1) @field:Max(4)` |
| `enum{A,B,C}` | enum 값 set |

---

## 1. error-cases (7 endpoints)

### 1.1 `POST /api/v1/error-cases` — 케이스 생성

Body: `application/json`

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `title` | body | `String` | ✓ | `NotBlank`, `≤200` | `"NullPointerException in OrderService.calc"` | 케이스 제목 |
| `project` | body | `String?` | ✗ | null OK (구 `scope` 에서 rename) | `"order-service"` | 프로젝트/서비스 식별자. nullable — 미지정 시 null 저장 |
| `paste` | body | `String?` | ✗ | null OK | `"java.lang.NPE at OrderService.calc..."` | 에러 원문(stacktrace) — null/blank 면 snapshot 추출 skip |
| `description` | body | `String?` | ✗ | null OK | `"@snippet(7a7d35e9) 참고."` | 본문 마크다운. `@snippet(id)`/`@attach(id)` 임베드 |
| `snippetMarkerIds` | body | `List<String>` | ✗ | 기본 `[]` | `["7a7d35e9"]` | 연결할 스니펫 markerId — *존재·소유·미연결* 검증 |
| `attachmentMarkerIds` | body | `List<String>` | ✗ | 기본 `[]` | `[]` | 연결할 첨부 markerId |
| `workspaceId` | body | `Long?` | ✗ | null OK | `1` | 지정 시 워크스페이스 WRITE+ 필요. null=개인 케이스 |
| `severity` | body | `Int?` | ✗ | `1..4`, null OK | `2` | 1=S1(Outage) … 4=S4(Info) |
| `tags` | body | `List<String>?` | ✗ | null OK, 각 ≤32자, 케이스당 max 20개, trim·소문자·중복 제거 | `["k8s","java","prod"]` | 자유 태그(구 `environment` 일반화). 생성 후 단건 추가/삭제는 1.6/1.7 |
| `occurredAt` | body | `LocalDateTime?` | ✗ | null OK, ISO-8601 | `"2026-05-27T19:42:00"` | 에러 발생 시각 |
| `visibility` | body | `Visibility` | ✗ | 기본 `PUBLIC`, enum{PUBLIC, PRIVATE, WORKSPACE} | `"PUBLIC"` | WORKSPACE 는 workspaceId 필수. workspaceId 있는 PUBLIC 은 워크스페이스 ADMIN 만 |

### 1.2 `GET /api/v1/error-cases` — 케이스 목록 (cursor)

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `workspaceId` | query | `Long?` | ✗ | null OK | `1` | 지정 시 그 워크스페이스 전체 (멤버여야), 미지정 시 본인 케이스 |
| `status` | query | `String?` | ✗ | enum{`OPEN`,`IN_PROGRESS`,`RESOLVED`,`DRAFT`,`CLOSED`} | `"OPEN"` | 잘못된 값 → 400 |
| `severity` | query | `Int?` | ✗ | (검증 없음) | `2` | 1..4 권장. 범위 밖이면 빈 결과 |
| `fingerprint` | query | `String?` | ✗ | null OK | `"a3f1..."` | SHA-256 hex. 같은 에러 묶어보기 |
| `cursor` | query | `String?` | ✗ | null OK, Base64URL | `"MjAyNi0wNS0yN1QxOTo0Mnwx"` | 이전 응답의 `nextCursor` 그대로 |
| `size` | query | `Int` | ✗ | 기본 20, max 100 | `20` | 페이지 크기 |

### 1.3 `GET /api/v1/error-cases/{id}` — 상세 조회

| 필드 | 위치 | 타입 | 필수 | 검증 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `id` | path | `Long` | ✓ | — | `15` | 케이스 ID |

### 1.4 `PATCH /api/v1/error-cases/{id}` — 부분 수정

Body: `application/json`. **모든 필드 nullable** — null/미포함 = 유지.

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `id` | path | `Long` | ✓ | — | `15` | |
| `title` | body | `String?` | ✗ | `≤200`, null=유지 | `"(updated) NPE"` | |
| `project` | body | `String?` | ✗ | null=유지 | `"order-service"` | 구 `scope` 에서 rename |
| `paste` | body | `String?` | ✗ | null=유지 | | 보내면 스냅샷/지문 재추출 |
| `description` | body | `String?` | ✗ | null=유지 | | |
| `severity` | body | `Int?` | ✗ | `1..4`, null=유지 | `3` | |
| ~~`environment`~~ | — | — | — | **제거됨** — `tags` 자유 태그로 일반화. 1.6/1.7 단건 endpoint 또는 아래 `tags` 일괄로 | — | |
| `tags` | body | `List<String>?` | ✗ | null=유지 / `[]`=전부 제거 / `[..]`=그 집합으로 치환. 각 ≤32자, max 20개. 서버에서 trim·소문자·중복 제거 | `["k8s","java","prod"]` | **선언형 재설정** — snippet/attachment 와 동일 패턴. 단건 추가/삭제는 1.6/1.7 도 사용 가능(공존) |
| `snippetMarkerIds` | body | `List<String>?` | ✗ | null=유지 / `[]`=전부 해제 / `[..]`=그 집합 | `["7a7d35e9","b2468aca"]` | **선언형 재연결** |
| `attachmentMarkerIds` | body | `List<String>?` | ✗ | 위와 동일 | | |
| `status` | body | `ErrorCaseStatus?` | ✗ | enum{`DRAFT`,`OPEN`,`IN_PROGRESS`,`RESOLVED`,`CLOSED`}, 도메인 전이 규칙 적용 | `"RESOLVED"` | 잘못된 전이 → 400 |
| `visibility` | body | `Visibility?` | ✗ | enum{PUBLIC,PRIVATE,WORKSPACE}, null=유지 | `"PUBLIC"` | 워크스페이스 케이스 → PUBLIC 승격 = 워크스페이스 ADMIN |

### 1.5 `DELETE /api/v1/error-cases/{id}` — 삭제(cascade)

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `id` | path | `Long` | ✓ | 케이스 ID. 소유자만. 스니펫·첨부·태그 함께 cascade |

### 1.6 `POST /api/v1/error-cases/{id}/tags` — 태그 1건 추가 (멱등)

Body: `application/json`. UI 의 *"엔터 입력 → 칩 생성"* 동작에 1:1 매핑. 권한: 케이스 WRITE.

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `id` | path | `Long` | ✓ | — | `15` | 케이스 ID |
| `tag` | body | `String` | ✓ | `NotBlank`, `≤32` | `"k8s"` | trim·소문자 자동 정규화. 이미 있으면 멱등 응답 |

응답 200: `{tag, added, removed:false, allTags}` (allTags = 정규화 후 케이스의 전체 태그). max 20개 초과 시 400.

### 1.7 `DELETE /api/v1/error-cases/{id}/tags/{tag}` — 태그 1건 제거 (멱등)

| 필드 | 위치 | 타입 | 필수 | 검증 | 비고 |
|---|---|---|---|---|---|
| `id` | path | `Long` | ✓ | — | 케이스 ID |
| `tag` | path | `String` | ✓ | URL-encoded 권장 | UI 의 *칩 X 버튼* 1회 클릭. 원래 없어도 200 |

응답 200: `{tag, added:false, removed, allTags}`. 권한: 케이스 WRITE.

---

## 2. error-snippets (3 endpoints)

### 2.1 `POST /api/v1/error-snippets` — 스니펫 생성

Body: `application/json`

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `title` | body | `String?` | ✗ | null OK | `"OrderService.calc"` | 미지정 시 'untitled' |
| `language` | body | `String` | ✓ | `NotBlank` | `"kotlin"` | highlight 용 식별자 |
| `filePathOrClass` | body | `String?` | ✗ | null OK | `"src/.../OrderService.kt"` | |
| `lineRange` | body | `String?` | ✗ | null OK | `"10-20"` | |
| `caption` | body | `String?` | ✗ | null OK | | 부가 설명 |
| `code` | body | `String` | ✓ | `NotBlank` | `"fun calc(): Int = 42"` | 본문 |

### 2.2 `PATCH /api/v1/error-snippets/{markerId}` — 내용 수정

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `markerId` | path | `String` | ✓ | — | `"7a7d35e9"` | |
| `title` | body | `String?` | ✗ | null=유지 | | |
| `language` | body | `String?` | ✗ | `≥1`(보내면 비어있음 X), null=유지 | `"kotlin"` | |
| `filePathOrClass` | body | `String?` | ✗ | null=유지 | | |
| `lineRange` | body | `String?` | ✗ | null=유지 | `"10-25"` | |
| `caption` | body | `String?` | ✗ | null=유지 | | |
| `code` | body | `String?` | ✗ | `≥1`(보내면 비어있음 X), null=유지 | `"fun calc(): Int = 99"` | |

### 2.3 `DELETE /api/v1/error-snippets/{markerId}` — 삭제(미연결만)

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `markerId` | path | `String` | ✓ | 업로더 본인 + `errorCaseId IS NULL` 만 |

---

## 3. error-attachments (3 endpoints)

### 3.1 `POST /api/v1/error-attachments` — 첨부 업로드

Body: `multipart/form-data` (DTO 사용 X — `@RequestPart` 로 직접 받음)

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 비고 |
|---|---|---|---|---|---|
| `file` | multipart | `MultipartFile` | ✓ | 비어있지 않음 (`require(!file.isEmpty)`), `originalFilename` 비어있음 X | 실제 파일 |
| `title` | multipart | `String?` | ✗ | null OK | 표시용 제목 |
| `caption` | multipart | `String?` | ✗ | null OK | 부가 설명 |

서버 자동 추출: `fileName`(file.originalFilename), `contentType`(file.contentType 또는 `application/octet-stream`), `size`(file.size).

⚠️ `presentation/web/dto/request/AttachmentUploadRequest.kt` 는 *사용되지 않음(dead code)*. 컨트롤러가 DTO 가 아닌 `@RequestPart` 직접 사용.

### 3.2 `GET /api/v1/error-attachments/{markerId}` — 다운로드/미리보기

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `markerId` | path | `String` | ✓ | — | `"f0c12ab9"` | |
| `download` | query | `Boolean` | ✗ | 기본 `false` | `true` | true=`attachment`, false=`inline` |

### 3.3 `DELETE /api/v1/error-attachments/{markerId}` — 삭제(미연결만)

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `markerId` | path | `String` | ✓ | 업로더 본인 + `errorCaseId IS NULL` 만 |

---

## 4. error-case-steps (4 endpoints)

### 4.1 `POST /api/v1/error-cases/{caseId}/steps` — Step 추가

Body: `application/json`

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | — | `15` | |
| `title` | body | `String` | ✓ | `NotBlank`, `≤200` | `"useEffect cleanup 추가"` | 카드 헤드라인 |
| `status` | body | `StepStatus` | ✓ | enum{`RESOLVED`,`IN_PROGRESS`,`FAILED`} | `"FAILED"` | 구 `SUCCESS/PARTIAL/FAILURE` rename |
| `attemptType` | body | `String?` | ✗ | `≤64`, null OK, **자유 String** | `"CODE_CHANGE"` 또는 `"DB-Migration"` | system 카탈로그(§7) 값 또는 본인 커스텀. 새 값이면 본인 카탈로그에 *자동 등록* |
| `body` | body | `String?` | ✗ | null OK, default null | | 자유 마크다운 |
| `insight` | body | `String?` | ✗ | `≤500`, null OK | | 한두 문장 요약 |

**응답**: `CreateStepResponse{step, caseStatus, suggestResolve}`. `status==RESOLVED` 이고 케이스가 `IN_PROGRESS` 면 `suggestResolve=true` — SPA 가 케이스 RESOLVED 전환 모달 표시.

### 4.2 `GET /api/v1/error-cases/{caseId}/steps` — Step 목록

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | `orderIndex ASC, id ASC` |

### 4.3 `PATCH /api/v1/error-cases/{caseId}/steps/{stepId}` — Step 수정

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 비고 |
|---|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | — | |
| `stepId` | path | `Long` | ✓ | — | |
| `title` | body | `String?` | ✗ | `≤200`, null=유지 | |
| `status` | body | `StepStatus?` | ✗ | enum{`RESOLVED`,`IN_PROGRESS`,`FAILED`}, null=유지 | |
| `attemptType` | body | `String?` | ✗ | `≤64`, null=유지. 새 값이면 본인 카탈로그(§7) 자동 등록 | `clearAttemptType=true` 면 명시적 null |
| `clearAttemptType` | body | `Boolean` | ✗ | 기본 `false` | true 면 attemptType 을 null 로 비움 |
| `body` | body | `String?` | ✗ | null=유지 | |
| `insight` | body | `String?` | ✗ | `≤500`, null=유지 | |

**응답**: `UpdateStepResponse{step, caseStatus, suggestResolve}`. 수정 후 `step.status==RESOLVED && case.status==IN_PROGRESS` 면 `suggestResolve=true`. **Breaking** — 기존은 `StepResponse` 단일 반환이었음.

### 4.4 `DELETE /api/v1/error-cases/{caseId}/steps/{stepId}` — Step 삭제

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | |
| `stepId` | path | `Long` | ✓ | 작성자 본인만. 참조하는 solution 있으면 409 |

---

## 5. error-case-solutions (3 endpoints)

### 5.1 `POST /api/v1/error-cases/{caseId}/solutions` — Solution 추가

Body: `application/json`

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | — | `15` | |
| `title` | body | `String` | ✓ | `NotBlank`, `≤200` | `"캐시 무효화 + cleanup 둘 다 적용"` | 한 문장 요약 |
| `stepIds` | body | `List<Long>` | ✓ | (Bean Validation 없음 — 도메인 검증), 빈 배열·중복·타 케이스 step → 400 | `[3, 5]` | **순서 유의미** |

### 5.2 `GET /api/v1/error-cases/{caseId}/solutions` — 목록

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | `createdAt ASC` |

### 5.3 `DELETE /api/v1/error-cases/{caseId}/solutions/{solutionId}` — 삭제

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | |
| `solutionId` | path | `Long` | ✓ | 작성자 본인 또는 케이스 소유자 |

---

## 6. error-case-comments (8 endpoints)

### 6.1 `POST /api/v1/error-cases/{caseId}/comments` — 댓글 작성

Body: `application/json`

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | — | `28` | |
| `parentCommentId` | body | `Long?` | ✗ | null=top-level, default null | `101` | 답글 대상. depth=1 강제 (서버 redirect) |
| `body` | body | `String` | ✓ | `NotBlank`, `≤5000` | `"이렇게 바꿔보면 어떨까요"` | 마크다운 |
| `quoteSourceKind` | body | `QuoteSourceKind` | ✗ | enum{`NONE`,`CASE_BODY`,`STEP`}, default `NONE` | `"STEP"` | 인용 종류 |
| `quoteSourceId` | body | `Long?` | ✗ | null OK | `3` | STEP → step id, 그 외 null |
| `quoteSnapshot` | body | `String?` | ✗ | `≤500`(서버 자동 truncate) | `"코드 일부..."` | CASE_BODY/STEP 시 필수 권장 |
| `suggestion` | body | `SuggestionRequest?` | ✗ | `@Valid`, null OK | (객체) | GitHub 스타일 Diff 제안 |
| `suggestion.sourceType` | body | `SuggestionSourceType` | ✓ (suggestion 보낼 시) | enum{`SNIPPET`,`MARKDOWN_CODE`} | `"SNIPPET"` | 코드 출처 종류 |
| `suggestion.sourceId` | body | `String` | ✓ (suggestion 보낼 시) | `NotBlank`, `≤128` | `"snippet-a1b2c3d4"` | opaque(BE 검증 X) |
| `suggestion.startLine` | body | `Int` | ✓ (suggestion 보낼 시) | `≥1` | `3` | 1-base |
| `suggestion.endLine` | body | `Int` | ✓ (suggestion 보낼 시) | `≥1`, ≥ startLine | `5` | |
| `suggestion.oldCode` | body | `String` | ✓ (suggestion 보낼 시) | `≤10000` | `"old"` | quoteSnapshot 동일 권장 |
| `suggestion.newCode` | body | `String` | ✓ (suggestion 보낼 시) | `≤10000` | `"new"` | 빈 문자열 = 삭제 제안 |

### 6.2 `GET /api/v1/error-cases/{caseId}/comments` — 댓글 목록

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | — | `28` | |
| `sort` | query | `String` | ✗ | enum{`NEWEST`,`OLDEST`,`HELPFUL`}, default `NEWEST` | `"NEWEST"` | HELPFUL 은 cursor 미지원 |
| `quoteKind` | query | `String` | ✗ | enum{`ALL`,`GENERAL`,`CASE_BODY`,`STEP`}, default `ALL` | `"ALL"` | |
| `cursor` | query | `String?` | ✗ | null OK, Base64URL | | 이전 응답의 nextCursor |
| `size` | query | `Int` | ✗ | 기본 20, max 100 | `20` | |

### 6.3 `PATCH /api/v1/error-cases/{caseId}/comments/{commentId}` — 댓글 수정

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 비고 |
|---|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | — | |
| `commentId` | path | `Long` | ✓ | — | |
| `body` | body | `String` | ✓ | `NotBlank`, `≤5000` | 작성자 본인만. 삭제된 댓글은 400 |

### 6.4 `DELETE /api/v1/error-cases/{caseId}/comments/{commentId}` — 댓글 삭제(soft)

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | |
| `commentId` | path | `Long` | ✓ | 작성자 본인만. 멱등 |

### 6.5 `POST /api/v1/error-cases/{caseId}/comments/{commentId}/helpful` — 도움됨 토글

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | |
| `commentId` | path | `Long` | ✓ | 케이스 read 권한자 누구나 토글 |

### 6.6 `POST /api/v1/error-cases/{caseId}/comments/{commentId}/reactions` — 리액션 추가

Body: `application/json`

| 필드 | 위치 | 타입 | 필수 | 검증 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | — | `28` | |
| `commentId` | path | `Long` | ✓ | — | `101` | |
| `emoji` | body | `String` | ✓ | `NotBlank`, `≤16` | `"👍"` | Slack 스타일. 같은 사용자 같은 이모지 멱등 |

### 6.7 `DELETE /api/v1/error-cases/{caseId}/comments/{commentId}/reactions/{emoji}` — 리액션 해제

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | |
| `commentId` | path | `Long` | ✓ | |
| `emoji` | path | `String` | ✓ | URL-encode 권장 (`%F0%9F%91%8D` for 👍) |

### 6.8 `PATCH /api/v1/error-cases/{caseId}/comments/{commentId}/suggestion/status` — Diff 제안 상태 변경

Body: `application/json`

| 필드 | 위치 | 타입 | 필수 | 검증 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `caseId` | path | `Long` | ✓ | — | | |
| `commentId` | path | `Long` | ✓ | — | | |
| `status` | body | `SuggestionStatus` | ✓ | enum{`PENDING`,`APPLIED`,`REJECTED`} | `"APPLIED"` | **케이스 owner 만** |

---

## 7. step-attempt-types (3 endpoints) · **NEW**

Step 의 `attemptType` 자유 String 카탈로그. system 7개(`CODE_CHANGE`/`CONFIG`/`DEPENDENCY`/`ENV`/`ROLLBACK`/`INVESTIGATION`/`OTHER`)는 모든 사용자에게 기본 제공, 본인 커스텀은 본인만 보이는 별도 카탈로그.

**자동 등록**: §4.1 / §4.3 에서 step 의 `attemptType` 이 system·custom 어디에도 없으면 본인 카탈로그에 멱등 add — 별도 호출 불필요. 명시 관리만 본 챕터 사용.

### 7.1 `GET /api/v1/step-attempt-types` — 카탈로그 조회

| 필드 | 위치 | 타입 | 필수 | 비고 |
|---|---|---|---|---|
| (없음) | — | — | — | principal 의 `sub` 로 본인 카탈로그 조회 |

응답 200: `[{name: "CODE_CHANGE", isSystem: true}, ..., {name: "DB-Migration", isSystem: false}]`. system 먼저 + 본인 custom(`createdAt ASC`) 평탄 list.

### 7.2 `POST /api/v1/step-attempt-types` — 커스텀 추가(멱등)

Body: `application/json`

| 필드 | 위치 | 타입 | 필수 | 검증 / 기본 | 예시 | 비고 |
|---|---|---|---|---|---|---|
| `name` | body | `String` | ✓ | `NotBlank`, `≤64`, lowercase 비교로 멱등 | `"DB-Migration"` | 표시용 원본 대소문자 유지. system 값과 충돌 시 400 |

응답 201: `{name, isSystem:false}`. 같은 normalized 키가 이미 있으면 *이미 있는 표시명을 그대로 유지* (대소문자 변경 X).

### 7.3 `DELETE /api/v1/step-attempt-types/{name}` — 커스텀 삭제

| 필드 | 위치 | 타입 | 필수 | 검증 | 비고 |
|---|---|---|---|---|---|
| `name` | path | `String` | ✓ | URL-encode 권장 | lowercase 비교. **system 값은 400**. 없는 값은 404 |

응답 204. 이미 저장된 step 의 `attemptType` String 값은 *유지* — 자동완성 후보에서만 사라짐 (느슨 결합).

---

## 8. 공통 인증 정보 (모든 endpoint)

| 항목 | 값 |
|---|---|
| 헤더 | `X-Internal-Auth: <internal JWT>` |
| 발급 주체 | 게이트웨이 (iss=gateway, aud=core-api, RS256) |
| `sub` claim | 사용자 ID (Long) — 컨트롤러에 `@AuthenticationPrincipal userId: Long` 으로 주입 |
| `exp` | 짧은 수명 (~60초~90초). 만료 시 401 |
| permitAll 경로 | `/actuator/health`, `/error`, `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` |

---

## 9. 검증된 모든 enum / 카탈로그 값 (참조용)

| Enum / Catalog | 값 | 위치 |
|---|---|---|
| `Visibility` | `PUBLIC` / `PRIVATE` / `WORKSPACE` | case (request·response 공통) |
| `ErrorCaseStatus` | `DRAFT` / `OPEN` / `IN_PROGRESS` / `RESOLVED` / `CLOSED` | case |
| `StepStatus` | `RESOLVED` / `IN_PROGRESS` / `FAILED` *(구 SUCCESS/PARTIAL/FAILURE)* | step |
| `AttemptType` *(자유 String — enum 아님)* | system 7개: `CODE_CHANGE` / `CONFIG` / `DEPENDENCY` / `ENV` / `ROLLBACK` / `INVESTIGATION` / `OTHER` + 본인 custom | step (카탈로그 §7) |
| `QuoteSourceKind` | `NONE` / `CASE_BODY` / `STEP` | comment |
| `SuggestionSourceType` | `SNIPPET` / `MARKDOWN_CODE` | comment.suggestion |
| `SuggestionStatus` | `PENDING` / `APPLIED` / `REJECTED` | comment.suggestion |
| (comment) `sort` | `NEWEST` / `OLDEST` / `HELPFUL` | query param |
| (comment) `quoteKind` filter | `ALL` / `GENERAL` / `CASE_BODY` / `STEP` | query param |

---

## 10. Swagger 어노테이션 보완 권고 (현재 누락된 곳)

| # | 위치 | 누락 / 문제 | 우선순위 |
|---|---|---|---|
| 1 | `attachment/.../AttachmentUploadRequest.kt` | **dead code** (컨트롤러가 `@RequestPart` 직접 사용). `size: Long` 에 `@field:NotBlank`(String 전용) 잘못 적용. 제거 권장. 또는 swagger `@Schema` 보완 후 활용 검토 | HIGH |
| 2 | ~~`step/.../UpdateStepRequest.kt`~~ | ~~`title`/`status`/`attemptType`/`body`/`insight` 에 `@Schema(description=...)` 부재~~ → **2026-06-06 해소** (자유 String 전환과 함께 @Schema 전체 작성) | DONE |
| 3 | `solution/.../CreateSolutionRequest.kt` | `stepIds` 에 `@field:Size(min=1)` + 검증 메시지 부재 (빈 배열은 도메인에서 잡음). swagger `Schema.RequiredMode.REQUIRED` 명시는 OK | MED |
| 4 | `case/.../CreateErrorCaseRequest.kt` | `description`/`workspaceId`/`severity`/`tags`/`occurredAt` 에 `requiredMode=NOT_REQUIRED` 명시 부재 (기본값이 NOT_REQUIRED 라 동작은 OK, 일관성 측면). `environment` 는 `tags` 로 일반화되어 제거됨 | LOW |
| 5 | `case/.../UpdateErrorCaseRequest.kt` | 모든 필드가 nullable 라 자동으로 NOT_REQUIRED — 명시 안 해도 OK | LOW |
| 6 | `snippet/.../CodeSnippetUpdateRequest.kt` | 모든 필드 잘 갖춤. OK | — |
| 7 | `comment/.../CommentRequests.kt` | 매우 잘 갖춤. OK | — |
| 8 | `case/.../ErrorCaseController.kt` `list()` | `severity` query 에 `@Min(1) @Max(4)` 적용 안 됨 (`@RequestParam(required=false) severity: Int?` 만). 범위 밖이면 빈 결과 — 사용자가 헷갈릴 수 있음. swagger 에 `minimum/maximum` 표기 X | LOW |
| 9 | `comment/.../CommentController.kt` `list()` | `sort`/`quoteKind` 가 enum 이 아니라 `String` 으로 받고 직접 valueOf — swagger UI 에 enum dropdown 안 뜸. `@Schema(allowableValues=[...])` 또는 `@Parameter(schema=@Schema(implementation=...))` 로 보완 가능 | LOW |
| 10 | `case/.../ErrorCaseController.kt` `list()` | `cursor`, `size` 의 `@Schema(example/min/max)` 부재 | LOW |

### 다음 단계 (이 문서를 만든 다음 진행할 작업)

- HIGH: `AttachmentUploadRequest.kt` 정리 (제거 vs swagger 보완 결정)
- MED: `UpdateStepRequest` `@Schema` 추가 · `CreateSolutionRequest.stepIds` `@Size(min=1)` + `@Schema`
- LOW: 일관성 — 모든 nullable 에 `requiredMode=NOT_REQUIRED` 명시 / `severity` query 에 `@Min/@Max` / list query 의 enum dropdown 지원

---

## 11. 관련 문서

- 댓글 시스템 입력 흐름 자세히: [`comment-discussion.md`](comment-discussion.md), [`comment-implementation.md`](comment-implementation.md)
- 첨부 라이프사이클: [`attachment-lifecycle.md`](attachment-lifecycle.md)
- 도메인 모델: [`errorcase-domain.md`](errorcase-domain.md)
- 변경 이력: 루트 [`docs/changelog.md`](../../docs/changelog.md)

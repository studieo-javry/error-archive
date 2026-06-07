# error-archive Implementation Changelog

## 2026-06-07 01:35 KST · core-api · feat: PATCH /error-cases 에 `tags` 선언형 일괄 재설정 추가

**요청 한 줄**: 에러 케이스 *수정* request 에 tag 필드가 없어 일괄 변경이 안 되는 문제. 단건 endpoint 와 공존하는 *선언형* 필드 추가.

**한 줄 요약**: `UpdateErrorCaseRequest.tags: List<String>?` 신설 — snippet/attachment 와 동일한 *선언형 재설정* 패턴(null=유지, []=전부 제거, [..]=그 집합으로 diff). UseCase 에 `ErrorCaseTagRepositoryPort` 주입 + `reconcileTags()` 추가 — `ErrorCase.normalizeTag()` 로 각 element trim·소문자·≤32 정규화, 결과 집합 크기 max 20 검증, current ∩ want 기준으로 add/remove diff 수행. 단건 `POST/DELETE /tags` endpoint(1.6/1.7)는 그대로 유지 — 칩 UI 의 단건 toggle 과 settings 페이지의 일괄 저장이 자연스럽게 공존.

**변경 파일**:
- DTO: `UpdateErrorCaseRequest`(tags 필드 + @Schema)
- Command: `UpdateErrorCaseCommand`(tags 필드)
- UseCase: `UpdateErrorCaseUseCase`(`tagRepository` 주입, `reconcileTags()` 추가 — snippet/attachment 패턴과 동형)
- Controller: `ErrorCaseController.update` request→command 매핑 1 line
- 문서: `core-api/docs/api-input-fields.md` §1.4 PATCH 표에 tags 행 추가(`environment` 취소선 옆에 일괄 옵션 안내)

**의미론**: snippet/attachment 와 동일하게 *원본이 무엇이든 desired 가 진실* — `current ∩ want` 외의 current 는 제거. 단건 endpoint 의 멱등성은 그대로(현재 멤버에 의존하지 않음). max 20 초과는 400 (`ErrorCaseLinkException`).

빌드 `:core-api:compileKotlin` 통과. e2e 별도 진행. `feature/#5-iam-auth-baseline` 브랜치 working tree 변경.


## 2026-06-07 01:10 KST · core-api + gateway · feat: Step `attemptType` enum → 자유 String + per-user 커스텀 카탈로그 (자동 등록)

**요청 한 줄**: step 의 `attemptType` 을 enum 에서 자유 String 으로 풀고, 현재 7개 값은 system 기본 카탈로그로 모두에게 제공, 사용자 커스텀 값은 본인만 보이게 분리. 한 번 추가한 커스텀은 이후 같은 사용자의 step 작성 시 자동 완성 후보로 재활용.

**한 줄 요약 (model)**: `AttemptType` 을 `enum class` → `object` 로 전환 — `SYSTEM: List<String>` 상수(`CODE_CHANGE/CONFIG/DEPENDENCY/ENV/ROLLBACK/INVESTIGATION/OTHER`), `isSystem()`, `normalize()`(trim·≤64), `normalizedKey()`(lowercase) 노출. `Step.attemptType`·`StepEntity.attemptType`·`CreateStepCommand`·`UpdateStepCommand`·`CreateStepRequest`·`UpdateStepRequest`·`StepResponse` 전부 `String?` 로 변경. DB `error_case_step.attempt_type` CHECK 제약 DROP + `VARCHAR(64)` 로 확장.

**한 줄 요약 (catalog)**: per-user 커스텀 테이블 `step_attempt_type_custom(user_id, name, normalized) UNIQUE(user_id, normalized)` 신설. 도메인 분리하지 않고 entity 만 두고 port `StepAttemptTypeCatalogPort`(findAllByUserId/add/remove/exists) 가 어댑터를 통해 직접 접근. system 값과 충돌하는 name 은 add/remove 모두 400.

**한 줄 요약 (자동 등록)**: `CreateStepUseCase`·`UpdateStepUseCase` 가 step 저장 후 `attemptType` 이 *system 도 본인 custom 도 아니면* port 로 멱등 add — 별도 명시 호출 불필요. 멱등 키는 `normalizedKey()`(lowercase). step 의 attemptType String 값은 카탈로그와 *느슨 결합* — custom 삭제해도 기존 step 의 값은 그대로 유지(자동완성 후보에서만 사라짐).

**새 endpoint 3개** (`/api/v1/step-attempt-types`, AUDIENCE_CORE_API):
- `GET` → `[{name, isSystem}]` system + 본인 custom 평탄 list (system 먼저, custom 은 createdAt ASC)
- `POST {"name":"..."}` → 201 멱등 add. system 값이면 400, 빈 값/>64자 400
- `DELETE /{name}` → 204. system 값 400, 본인 카탈로그에 없으면 404 (lowercase 비교)

**Gateway 라우팅**: `HeaderInjectionFilter.resolveAudience()` 와 `RouteConfig.coreApiRoute` 둘 다 `/api/v1/step-attempt-types` 를 core-api 로 매핑(기존 audience 매핑 누락 패턴 반복 회피).

**변경/신규 파일**: 도메인(`AttemptType` rewrite, `Step` String 화) · application(`StepCommands`, `CreateStepUseCase`·`UpdateStepUseCase` port 주입 + 자동 등록 로직, 신규 `StepAttemptTypeUseCases`, 신규 port `StepAttemptTypeCatalogPort`) · infrastructure(신규 `StepAttemptTypeCustomEntity`·`Jpa`·`Adapter`, `StepEntity` `@Enumerated` 제거 + length 64) · presentation(`StepRequests`·`StepResponses` 자유 String, 신규 `StepAttemptTypeRequest`·`StepAttemptTypeResponse`, 신규 `StepAttemptTypeController`) · gateway(`HeaderInjectionFilter`, `RouteConfig`) · DB(DROP CHECK + ALTER TYPE + CREATE TABLE step_attempt_type_custom 수동 실행 완료).

**검증 (gateway:8000 user=777 JWT, e2e 7건)**:
- GET `/step-attempt-types` (초기) → system 7개만 ✓
- POST `/error-cases/33/steps {attemptType:"DB-Migration"}` → 201 → GET catalog 에 `DB-Migration isSystem:false` 자동 등장 ✓
- POST `/step-attempt-types {"name":"infra tweak"}` → 201 ✓
- POST `/step-attempt-types {"name":"INFRA TWEAK"}` (멱등) → 201, 기존 표시 유지 ✓
- POST `/step-attempt-types {"name":"CODE_CHANGE"}` → 400 (system 충돌) ✓
- DELETE `/step-attempt-types/CODE_CHANGE` → 400 (system) ✓
- DELETE `/step-attempt-types/DB-Migration` → 204 ✓ / 없는 값 → 404 ✓

빌드 `:core-api:compileKotlin` · `:gateway:compileKotlin` 통과 · core-api/gateway 재시작 · e2e 통과. `feature/#5-iam-auth-baseline` 브랜치 working tree 변경 (commit 사용자 직접).


## 2026-06-06 17:36:11 KST · core-api · feat: 자유 태그 시스템 + Step status rename(RESOLVED/IN_PROGRESS/FAILED) + Step PATCH 자동 추천

**요청 한 줄**: (A) `environment` 단일 필드를 자유 태그 리스트로 일반화, 생성/상세 양쪽에서 단건 add/remove 가능. (B) Step status `SUCCESS/PARTIAL/FAILURE` → `RESOLVED/IN_PROGRESS/FAILED`. (C) Step 을 RESOLVED 로 PATCH 하고 케이스가 IN_PROGRESS 면 응답에 `suggestResolve=true` hint.

**한 줄 요약 (A 태그)**: 별도 테이블 `error_case_tag(error_case_id, tag) UNIQUE` + `ix_error_case_tag_tag` + ON DELETE CASCADE 신설. 정규화 = trim·소문자·≤32자·distinct, 케이스당 max 20개. **새 endpoint 2개**: `POST /error-cases/{id}/tags {"tag":"k8s"}` (멱등 추가, 응답에 전체 태그) · `DELETE /error-cases/{id}/tags/{tag}` (멱등 삭제). 케이스 생성 시 `tags:["..","K8S","  java  "]` 배열도 받아 일괄 정규화·저장. 권한 = 케이스 WRITE. `Meta.environment` · `meta_environment` 컬럼 · `MetaEmbeddable.environment` 제거.

**한 줄 요약 (B Step status)**: enum `StepStatus = RESOLVED/IN_PROGRESS/FAILED`. DB 마이그레이션 `error_case_step` (7 row): SUCCESS→RESOLVED, PARTIAL→IN_PROGRESS, FAILURE→FAILED + CHECK 제약 갱신. RESOLVED 추천 트리거 (`existsSuccessByErrorCaseId`·`CreateStepUseCase.suggestResolve`) 도 `StepStatus.RESOLVED` 로.

**한 줄 요약 (C Step PATCH 자동 추천)**: `UpdateStepUseCase` 가 `Step` 단일 반환 → `Result(step, caseStatus, suggestResolve)` 로 확장. case 다시 조회 + `step.status == RESOLVED && case.status == IN_PROGRESS` 시 true. 응답 DTO `UpdateStepResponse` 신설. `StepController.update` 응답 형태 변경(Breaking — 기존은 StepResponse 단일). 자동 전이 X — 사용자가 확인 후 별도 PATCH /error-cases/{id} `{status:"RESOLVED"}` 호출.

**변경 파일 (~20)**: 도메인(`ErrorCase`·`Meta`·`StepStatus`) · port·adapter(`ErrorCaseTagRepositoryPort`·`Adapter` 신설, `ErrorCaseRepositoryAdapter` 가 `findById` 시 태그 조회) · entity·jpa(`ErrorCaseTagEntity`·`Jpa`, `MetaEmbeddable` env 제거) · use case(`Create/UpdateErrorCaseUseCase` env 제거+tags 정규화/저장, `Add/RemoveErrorCaseTagUseCase` 신설, `UpdateStepUseCase` caseStatus/suggestResolve, `DeleteErrorCaseUseCase` 태그 cascade, `Create/StepRepositoryAdapter` RESOLVED 매핑) · DTO(`CreateErrorCaseRequest.tags`/`AddTagRequest`/`TagsResponse`/`UpdateStepResponse` 신설, `ErrorCaseDetailResponse.environment` → `tags`, `UpdateErrorCaseRequest.environment` 제거, `StepRequests` example RESOLVED/FAILED) · 컨트롤러(`ErrorCaseController` 신규 2 endpoint + env 매핑 제거, `StepController.update` 응답 갱신) · DB(`CREATE TABLE error_case_tag` + `DROP meta_environment` + step UPDATE + CHECK 갱신).

**검증 (gateway:8000 사용자 JWT)**:
- `POST {"title":"x","tags":["K8s","  java  ","prod","K8S"]}` → 201 → GET 응답 `"tags":["k8s","java","prod"]` ✓
- `POST /tags {"tag":"Postgres"}` → `{tag:"postgres", added:true, allTags:[..."postgres"]}` ✓
- `DELETE /tags/k8s` → `{tag:"k8s", removed:true, allTags:[...없음]}` ✓
- Step PATCH `{status:"RESOLVED"}` → `{step:{...,status:"RESOLVED"}, caseStatus:"IN_PROGRESS", suggestResolve:true}` ✓
- Step 생성 시 `status:"FAILED"` 정상 (enum rename OK) ✓

**UX 추천 (FE 적용 권고)**: 상세 페이지 메타 영역에 *인라인* 태그 위젯(별도 편집 모드 X) — 칩 리스트 + 끝에 `+` 인풋. enter→`POST /tags` (낙관적 UI 로 즉시 칩 추가, 실패시 토스트), 칩 hover X→`DELETE`. 동일 위젯을 생성 페이지에선 *로컬 state* 로 사용해 submit 시 한꺼번에 전송. Phase 2 에서 인기 태그 dropdown 추가 가능(별도 endpoint 필요).

빌드 통과 · core-api 재시작 · e2e 5건 통과. `feature/#5-iam-auth-baseline` 브랜치 working tree 변경 (commit 직접).


## 2026-06-06 17:07:30 KST · core-api · refactor: ErrorCase `scope` → `project` rename + `paste`/`project` nullable

**요청 한 줄**: ErrorCase 생성 API 에서 `scope` 필드명을 `project` 로 바꾸고, `paste`·`project` 를 nullable 로.

**한 줄 요약**: 전체 stack 일관(API → Command → Domain → Entity → DB) 으로 **`scope` → `project`** rename. 동시에 `paste`·`project` 의 *Bean Validation `@NotBlank` 제거 + 코틀린 nullable* 로 전환 — *둘 다 없는 최소 요청* `{"title":"x"}` 도 201 통과 가능. paste 가 null/blank 이면 `buildSnapshot` 호출 자체를 건너뛰고 snapshot=null 로 저장. 변경 11 파일 + DB 1 컬럼.

**변경 파일** (한 PR 분량):
- DTO: `CreateErrorCaseRequest`(`scope:String` non-null → `project:String?`, `paste:String` non-null → `paste:String?`. `@NotBlank` 제거. 설명/example 갱신) · `UpdateErrorCaseRequest`(`scope` → `project`)
- Command: `CreateErrorCaseCommand`(`scope:String → project:String?`, `paste:String → paste:String?`) · `UpdateErrorCaseCommand`(rename)
- UseCase: `CreateErrorCaseUseCase`(paste null-safe `command.paste?.takeIf{it.isNotBlank()}?.let{buildSnapshot(it)}`, `command.project` 매핑) · `UpdateErrorCaseUseCase`(`command.project ?: errorCase.project`)
- Domain: `ErrorCase`(field·create·reconstitute·update 시그니처 6곳 rename)
- Infra: `ErrorCaseEntity`(`var project: String?`) · `ErrorCaseRepositoryAdapter`(toEntity/toDomain 2곳)
- Response: `ErrorCaseDetailResponse`(필드 + from 매핑)
- Controller: `ErrorCaseController` request→command 매핑 2곳
- DB: `ALTER TABLE error_case RENAME COLUMN scope TO project` (수동 실행 완료)

**검증** (gateway:8000 거쳐 사용자 JWT 흐름):
- `POST {"title":"test rename","project":"order-service"}` → 201
- `POST {"title":"minimal"}` (project·paste 둘 다 미포함) → 201
- `GET /error-cases/{id}` 응답에 `"project":null` 노출

빌드(`./gradlew :core-api:compileKotlin`) 통과 · core-api 재시작 후 e2e 통과. `feature/#5-iam-auth-baseline` 브랜치 working tree 변경 (commit 은 사용자 직접).


## 2026-06-06 16:30:00 KST · gateway · fix: `/api/v1/error-snippets` audience 매핑 누락 — 401 → 201

**요청 한 줄**: code-snippet 생성 API 가 gateway 통해 동작하는지 점검.

**한 줄 요약**: 점검 중 버그 발견. `gateway/HeaderInjectionFilter.resolveAudience` 가 `/api/v1/error-cases` · `/api/v1/error-attachments` 만 `aud=core-api` 로 매핑하고 **`/api/v1/error-snippets` 는 `else` 분기로 떨어져 `aud=iam-api`** 로 internal JWT 발급 → core-api 가 `expectedAudience=core-api` 와 mismatch → **HTTP 401**. (라우팅 자체는 core-api 로 정상 forward 되지만 토큰 audience 가 틀려 차단). 수정: `resolveAudience` 매칭에 `/api/v1/error-snippets` 추가 + 주석 "*새 endpoint 추가 시 여기 갱신 필수*" 박아둠. 재빌드+gateway 재시작 후 동일 요청 → **HTTP 201** + markerId 발급 정상.

**검증**: 사용자 JWT(HS256) → gateway:8000 → audience 결정 → internal JWT(RS256, gateway 키, aud=core-api) 발급 → core-api:8081 → InternalTokenAuthenticationFilter 검증(gateway 공개키, aud=core-api 일치, exp 통과) → CreateSnippetUseCase 실행 → 201. 사전 키 쌍 정합도 modulus 비교로 확인됨(`871B05B3AAE67D...646BF9`).

**잠재 우려 (다음 라운드)**: audience 매핑이 *gateway routing 규칙과 분리* 되어 있어 새 endpoint 추가 시 *두 곳을 모두 갱신* 해야 — Spring Cloud Gateway route metadata 에 audience 명시해서 *routing 한 곳에서 audience 도 결정* 하는 리팩토링 권고.


## 2026-06-05 15:44:10 KST · iam-api · docs+refactor: 클라이언트 → 서버 입력 필드 종합표 + Swagger 보완

**요청 한 줄**: core-api 외 *모든 API* (iam-api 포함) 에도 같은 입력 필드 종합표 작성 — Swagger 명시 + 문서 기록.

**한 줄 요약**: 새 문서 `iam-api/docs/api-input-fields.md` 작성 — **29 endpoint × 모든 입력 필드** (auth-oauth 2 / auth-token 2 / users-me 6 / social-follow 5 / workspaces 5 / workspace-invitations 5 / workspace-members 4) 를 위치(path/query/body/cookie/header) · 타입 · 필수 · null · 검증 · 예시 · 비고 컬럼으로 정리. iam-api 특유의 인증 패턴(Bearer JWT + refresh 쿠키 + OAuth 공개 + 일부 viewer-aware public GET) 도 명시.

**코드 보완 (HIGH)**: DTO 6개에 `@Schema` 어노테이션 추가:
- `StartOAuthRequest` — `redirectUri`/`returnUrl` (open-redirect 방어 규칙 swagger 노출 + `@field:Size(max=512)`)
- `OAuthCallbackRequest` — `code`/`state`/`redirectUri`/`rememberMe` 모두 description/example/requiredMode
- `CreateWorkspaceRequest` — `name`
- `UpdateWorkspaceRequest` — `name`/`notificationEnabled`/`defaultTimezone`
- `CreateInvitationRequest` — `type`/`role`/`email`/`expiresInHours`. **enum-as-String** 두 필드(`type`, `role`)에 `allowableValues=["EMAIL","LINK"]` / `["READ","WRITE"]` 명시 → Swagger UI 에 dropdown 노출
- `AcceptInvitationRequest` — `token`
- `ChangeMemberRoleRequest` — `role` 에 `allowableValues=["READ","WRITE","ADMIN"]` 명시

**LOW 보완 (문서에만 등재, 다음 라운드)**: `FollowController.followers/following` 의 `page`/`size` 에 `@Min/@Max` 검증 / `OAuthController.authorize` 의 `provider` path 에 `@Parameter(allowableValues=...)` 명시.

**모노레포 점검 결과**: insight-api/noti-api/publish-api 는 controller 0개(scaffolding 단계), gateway 의 1 controller 는 circuit breaker fallback handler (클라이언트 직접 호출 endpoint 아님) — 실제 정리 대상은 **core-api(이미 어제 완료) + iam-api(이번)**.

빌드 통과(`./gradlew :iam-api:compileKotlin`). cross-link: core-api 의 같은 종합표.


## 2026-06-05 15:10:14 KST · core-api · docs+refactor: 클라이언트 → 서버 입력 필드 종합표 + Swagger 보완

**요청 한 줄**: 모든 API 의 클라이언트 입력 필드를 한 눈에 (필수/null/타입/검증) — 가능하면 Swagger 에 명시 + 문서로도 기록.

**한 줄 요약**: 새 문서 `core-api/docs/api-input-fields.md` 작성 — **26 endpoint × 모든 입력 필드** 를 위치(path/query/body/multipart) · 타입 · 필수 여부 · null 허용 · 검증 제약 · 예시 · 비고 컬럼으로 정리. 6 controller (error-cases·error-snippets·error-attachments·steps·solutions·comments) 별 섹션 + 공통 인증 + enum 값 set + Swagger 보완 우선순위표.

**코드 보완 (HIGH/MED)**:
- (HIGH) `attachment/.../AttachmentUploadRequest.kt` **삭제** — dead code (컨트롤러가 `@RequestPart` 직접 사용. `size: Long` 에 `@field:NotBlank`(String 전용) 잘못 적용된 상태). 사용처 grep 0건 확인.
- (MED) `step/.../UpdateStepRequest` 의 5 필드(`title`/`status`/`attemptType`/`body`/`insight`)에 `@field:Schema(description=..., example=...)` 추가 — Swagger UI 에 설명 노출.
- (MED) `solution/.../CreateSolutionRequest.stepIds` 에 `@field:NotEmpty` + `@Schema(minLength=1)` — 빈 배열 금지를 *Bean Validation 단* 에서도 강제 + Swagger 에 명시.

**LOW 보완 (문서에만 등재)**: `CreateErrorCaseRequest` 의 nullable 필드 `requiredMode=NOT_REQUIRED` 명시 / list endpoint 의 `severity` query 에 `@Min/@Max` / comment list 의 `sort`/`quoteKind` 가 `String` 대신 enum 으로 받아 dropdown 지원. 우선순위 낮아 다음 라운드.

빌드 통과(`./gradlew :core-api:compileKotlin`). 문서 cross-link: 댓글 시스템·첨부 라이프사이클·도메인 모델.


이 문서는 `error-archive` 모노레포(core-api / iam-api / insight-api / noti-api / publish-api 통합) 안의 **모든 서브 프로젝트**에서 일어난 코드 추가/변경을 시간순으로 기록한다. 커밋 메시지보다 한 단계 위의 "구현 의도와 범위"를 사람이 읽을 수 있게 정리하는 게 목적.

작성 규칙:
- 시간은 **KST(UTC+9)**, 초 단위까지.
- 한 항목 = 한 번의 사용자 요청 + 그에 대한 구현.
- 헤더는 `## YYYY-MM-DD HH:MM:SS KST · <service> · <type>: <한 줄 제목>` 형식. `<service>`는 `iam-api`/`core-api`/`insight-api`/`noti-api`/`publish-api`/`repo`(루트 빌드/문서) 중 하나.
- 한 변경이 여러 서비스에 걸치면 `<service>`를 `multi`로 두고 본문에 영향 받은 서비스를 명시한다.
- 새로 만든 파일은 **추가**, 기존 파일을 손본 건 **수정**으로 표기.
- 동작 영향이 큰 변경(스키마/엔드포인트/계약)은 **Breaking** 표기.
- 관련 문서/엔드포인트/요청 의도 한 줄 요약을 헤더에 둔다.

> 2026-04-29 이전 항목들은 `service`가 도입되기 전에 작성되었다 — 모두 `iam-api`로 간주.

---

## 2026-05-30 13:44:47 KST · core-api · docs: 재검토 결정 문서에 §6 "홈 키워드 / `:insight-api` 분리" 등재

**요청 한 줄**: 홈 "오늘의 키워드" 의 정의·가공·시각화·서비스 분리 결정을 문서에 등재.

**한 줄 요약**: `core-api/docs/revisitable-decisions.md` 에 §6 추가. (a) 키워드 정의(fingerprint cluster·exception·library tag), (b) source — *내부 사용자 데이터*(외부 크롤링 X, 정체성 강화), (c) 의미의 3축(구체성·트렌딩·해결가능성), (d) 5단계 파이프라인(추출→정규화→집계→랭킹→표시) + 3D 큐브(시간×종류×스코프), (e) UI 시각화 6섹션 + 카드 단위 + 키워드 상세 페이지, (f) 데이터 모델(`hot_keyword_snapshot` + `hot_keyword_editor_pick`), (g) **`:insight-api` 활성화 — HTTP polling 동기화**(이전 권고 "core-api 안 sub-module" 정정), (h) search/matching 과의 통합 시점 — *Discovery BC* 가 함께 분리, (i) Phase 1 (편집자 픽 / 지금 해결 중 / 오늘 해결됨 3섹션), (j) 권한 누수·어뷰징·콜드 스타트 함정, (k) 재검토 트리거. §4 (Solution 매칭) 에 본 §과 같은 BC 분리 트리거 그룹임을 cross-link.

코드 변경 없음(문서만). insight-api scaffolding 은 사용자 명시 요청 시 진행.

## 2026-06-02 15:52:50 KST · core-api · feat: 댓글 테스터 — Slack 스타일 이모지 picker + 토글 UX 명확화

**요청 한 줄**: 이모지 + 추가 버튼이 prompt() 대신 Slack 같은 picker 띄우게 + 이미 내가 추가한 이모지는 다시 클릭으로 해제.

**한 줄 요약**: `comment-tester.html` FE 단독 수정. **(1) 이모지 picker** — `+` 버튼 클릭 시 320×380 popover 모달 + 5 카테고리 탭(자주/얼굴/제스처/오브젝트/기호, 각 16개) + 검색(키워드/이모지 직접 매칭) + 직접 입력란 + ESC/외부 클릭 닫기. **(2) 토글 UX 명확화** — picker 안 그리드에서 *내가 이미 reacted 한 이모지* 는 강조(`.mine`, 파란 outline)되고 클릭 시 자동으로 DELETE(해제). 기존 칩 클릭의 토글 동작은 그대로 두되 `title` 속성에 `"클릭으로 해제 · reacted by: #777, #888"` 같은 hover hint 추가해서 *동작 의도* 가 UI 에서 보이게.

**구현 로직**: picker 의 `pickEmoji(emoji)` 가 `mineEmojisFor(commentId)` 로 현재 내 reacted set 을 만든 뒤 — 포함되면 `DELETE /reactions/{emoji}`, 안 포함되면 `POST /reactions {emoji}`. BE 의 add/remove 가 이미 멱등이라 *BE 변경 없음* — FE 의 *판단* 만 수정. 검색 input 의 Enter / 직접 입력 박스로 임의 이모지 추가 가능(서버 `EMOJI_MAX=16` 제한 그대로). EMOJI_KEYWORDS 맵 — 자주 쓰는 50+ 이모지에 한/영 키워드 매핑.

**변경 파일**: `comment-tester.html` 한 파일 (CSS `.emojiPicker`/`.emojiCell` 등 + DOM `#emojiPicker`/`#emojiBackdrop` + JS `EMOJI_CATEGORIES`/`EMOJI_KEYWORDS`/`openEmojiPicker`/`renderEmojiPicker`/`pickEmoji`/`mineEmojisFor` + 기존 `renderReactions` 의 chip title hint + `wireCommentButtons` 의 `[data-react-add]` 핸들러 `prompt` → `openEmojiPicker`). BE 변경 0. 빌드 영향 0(static 리소스). 페이지는 새로고침만으로 반영(Spring static handler 디스크 직접 read).

## 2026-06-02 15:13:15 KST · core-api · feat: 댓글 시스템 통합 테스터 HTML — 백엔드 API 직접 연동

**요청 한 줄**: 댓글 시스템 기능을 백엔드 API 와 연동해 직접 브라우저로 테스트할 페이지 작성.

**한 줄 요약**: `core-api/src/main/resources/static/comment-tester.html` 신설. core-api 와 *same-origin*(`localhost:8081`) 이라 CORS 회피. 모든 댓글 endpoint(8개) + 케이스 상세 + Step 목록 연동. 디자인은 기존 두 HTML mockup(Concept-Comment-Discussion + BlogConcept33 gutter diff)의 통합 — 본문/Step 드래그 인용, 코드블럭 gutter 드래그 Diff 제안, depth=1 답글, Slack 스타일 이모지 리액션, 도움됨 stack popover, 편집/삭제, Diff 상태 변경(케이스 owner UI 노출), 필터/정렬, 다크모드.

**인증 흐름**: 게이트웨이를 띄우지 않고 브라우저에서 직접 호출하기 위해 **`DevHeaderAuthFilter`** 신설(`@Profile("local")` 한정). `X-Test-User-Id` 헤더 한 개로 인증 — `SecurityConfig` 가 `ObjectProvider<DevHeaderAuthFilter>` 로 받아 local 프로필일 때만 `InternalTokenAuthenticationFilter` 앞에 추가. dev/stg/prod 에서는 bean 자체가 생성 안 됨 → 보안 위험 0. 헤더 부재 시 기존 X-Internal-Auth 정상 경로 그대로 동작.

**변경 파일**: `DevHeaderAuthFilter.kt`(신규, local 한정) · `SecurityConfig.kt`(ObjectProvider 주입 + comment-tester.html permitAll + DevAuthFilter 앞에 위치) · `comment-tester.html`(신규, 700+ 줄, 단일 파일).

**기능 매핑**: GET `/error-cases?size=50`(내 케이스 list) → 케이스 선택 / GET `/error-cases/{id}` + `/steps`(상세+step) → 케이스 헤더 · 본문 · 스니펫 reviewableCode · Step 타임라인 · Step body 안 markdown 코드블럭도 reviewableCode 로 enhance / GET `/error-cases/{id}/comments?sort=&quoteKind=&size=`(트리·필터·정렬) / POST `/comments`(suggestion 동시 첨부) / PATCH `/comments/{id}` · DELETE `/comments/{id}`(soft) / POST `/helpful`(토글) / POST·DELETE `/reactions/{emoji}` / PATCH `/suggestion/status`(케이스 owner 한정 UI 노출). 인용문 클릭 → snapshot 첫 매치 위치로 `scrollIntoView` + 글로우. 매치 실패 시 토스트.

**서버 재시작 필요**: 현재 떠있는 인스턴스(PID 50752)는 옛 코드 — `./gradlew bootRun` 또는 IDE 에서 재시작 필요. 접속: `http://localhost:8081/comment-tester.html`.

빌드(`./gradlew compileKotlin`) 통과. 문서 갱신은 본 테스트 도구가 production 변경이 아니라 *최소* — changelog 만.

## 2026-06-02 14:48:57 KST · core-api · docs: 비로그인 노출 정책 — Phase 단계 등재 (revisitable §10)

**요청 한 줄**: 비로그인 사용자에게 어느 범위까지 PUBLIC 케이스를 노출할지 분석 + 정책 등재.

**한 줄 요약**: `revisitable-decisions.md` §10 신설 — "비로그인(익명) 사용자 노출 범위 — Phase 단계 등재". **상태: Phase 1 유지(현재 = 비로그인 차단)**. 코드 변경 없이 Phase 2-4 정책·트리거·작업·결정점을 미리 박아둠.

**핵심 결정**:
- **Phase 1 (현재)**: 비로그인 차단 유지. 콘텐츠 25건 + sanitize 인프라 부재 — SEO 평판 손상 위험 회피.
- **Phase 2 트리거**: PUBLIC 케이스 100+건 + 정성 평가("후회 안 할 만큼 알찬가"). 채택 시 **옵션 B** (PUBLIC 단건 + 댓글 read + Diff 제안 read).
- **Phase 3**: 검색 유입 월 1K+ → 옵션 C (+검색·목록).
- **Phase 4**: 사용자 100+ → 옵션 D (+Discovery·공개 프로필).
- 댓글 *작성* / 리액션 / 도움됨 / Diff 제안 작성 / 케이스 작성·수정·삭제 / `/me/*` / `/workspaces/*` 는 **항상 로그인** — 양질 토론·모더레이션 비용 측면.

**미리 정해둔 결정점 5개**: (1) Phase 2 트리거 임계(100건+정성) (2) **민감정보 sanitize 는 작성 시점** (FE redaction + BE 검증, AWS key·DB conn·email·IPv4·private domain 정규식) (3) PUBLIC→PRIVATE 회수 정책(즉시 비공개 + Search Console 자동 회수 요청 옵션) (4) **비인증 endpoint 는 별도 경로** (`/api/v1/public/**`) — SecurityFilterChain 분기 단순화, 같은 경로 + 권한 분기는 ACL 부담 (5) 공개 프로필 **기본 OFF + opt-in**.

**숨겨진 비용 8개** 명시: sanitize · PUBLIC→PRIVATE 회수 · robots.txt/sitemap · meta tags SSR · rate limit · SecurityFilterChain 비인증 분기 · GDPR/개인정보 표시 · 콘텐츠 품질 게이트.

**채택하지 않은 안**: Phase 2 지금 도입(인프라 부재) · 영구 차단(서비스 특성 충돌) · 비로그인 댓글 작성(모더레이션 폭주) · 같은 경로+권한 분기(ACL 부담).

코드 변경 없음(문서만). Phase 2 도입 시 후속 작업으로 `ErrorCaseAccess.requireRead` 시그니처 갱신(nullable userId 또는 별도 entry) + `comment-discussion §6`·`errorcase-domain §6` 갱신 박아둠.

## 2026-06-02 14:28:21 KST · core-api · refactor: `WorkspaceRole` 권한 hierarchy 명시화 — enum 책임으로 일원화

**요청 한 줄**: `IamWorkspaceQueryAdapter.getViewerRole` 가 권한 hierarchy(ADMIN > WRITE > READ)를 반영하는지 확인 + enum 자체에 hierarchy 메서드 추가.

**한 줄 요약**: getViewerRole 자체는 iam-api 가 부여한 *단일 role* 을 패스스루만 하고, hierarchy 는 호출 측에 *세 가지 패턴 혼재* (`!= null` / `canWriteContent()` / `== ADMIN`) 로 *부분적·간접* 표현이었음. **`WorkspaceRole` enum 에 hierarchy 메서드 추가** — `meetsOrExceeds(required)` (ordinal 기반) · `canRead()` · `canWriteContent()`(기존 시그니처 유지, 내부를 `meetsOrExceeds(WRITE)` 로 통일) · `canAdminister()`. 호출처 5개 모두 enum 직접 비교 제거하고 hierarchy 메서드로 일원화 — `requireRead` WORKSPACE 분기는 `?.canRead() == true`, `requireWrite` 그대로(`canWriteContent()`), `requirePublicPromotion` 의 owner/비owner 중복 2분기를 `?.canAdminister() == true` 단일 분기로 단순화, `CreateErrorCaseUseCase` PUBLIC 생성 검사는 `!role.canAdminister()`, `ListErrorCasesUseCase` 멤버 검사는 `?.canRead() != true`.

**변경 파일**: `WorkspaceQueryPort.kt`(enum hierarchy 메서드 4개) · `ErrorCaseAccess.kt`(3 메서드 모두) · `CreateErrorCaseUseCase.kt` · `ListErrorCasesUseCase.kt`. 빌드(`./gradlew compileKotlin`) 통과.

**효과**: (a) 신규 호출처가 일관 패턴(`canX()`) 한 가지만 따르면 됨. (b) 새 role 추가 시 컴파일러가 enum 한 곳만 갱신하면 됨. (c) `requirePublicPromotion` 의 중복 분기 제거로 코드 라인 절감. (d) iam-api 응답이 단일 role 만 주는 사실은 변경 없음 — 변환만 hierarchy-aware.

**규칙**: 신규 호출처는 **enum 직접 비교(`== ADMIN`) 금지, hierarchy 메서드 사용**.

문서: `errorcase-domain.md` §6 권한 모델 코드 블록 갱신 · `revisitable-decisions.md` §9 끝에 "2026-06-02 후속: `WorkspaceRole` hierarchy 명시화" 표 등재(Before/After 5 호출처 매핑).

## 2026-06-01 21:58:34 KST · core-api · feat: ErrorCase Visibility (PUBLIC/PRIVATE/WORKSPACE) 도입 — 댓글 권한 정합

**요청 한 줄**: 워크스페이스에 속하지 않은 개인의 PUBLIC 케이스에 댓글이 달릴 수 있게 — `ErrorCaseAccess.requireRead` 에 visibility 분기 추가 + 도메인에 visibility 모델 신설.

**한 줄 요약**: `ErrorCase` 도메인에 `Visibility` enum(PUBLIC/PRIVATE/WORKSPACE) 신설. **신규 케이스 기본값 PUBLIC**(공유 자산화 정체성). `ErrorCaseAccess.requireRead` 가 PUBLIC=로그인 누구나 / WORKSPACE=멤버 / PRIVATE=owner 로 분기 → 댓글 시스템은 *변경 없이* PUBLIC 케이스에서 누구나 댓글 가능해짐(단일 진입점 효과). **워크스페이스 케이스 → PUBLIC 승격은 워크스페이스 ADMIN 만**(`requirePublicPromotion` 신규, `revisitable §4` Solution 매칭 정책과 일관). Diff 제안 상태 변경은 여전히 owner 만(Visibility 무관). PUBLIC 케이스도 *로그인은 요구*(기존 JWT 보안 정책 유지).

**변경 파일**: 도메인(`Visibility.kt` 신규 · `ErrorCase` 필드+`create`/`reconstitute`/`update` 시그니처+invariant) · 응용(`ErrorCaseAccess` 3 메서드 + `requirePublicPromotion` · `Create/UpdateErrorCaseCommand` · `CreateErrorCaseUseCase` WORKSPACE+workspaceId 일관성·PUBLIC 생성 ADMIN 검사 · `UpdateErrorCaseUseCase` PUBLIC 승격 검사·visibility 변경 · `GetErrorCaseUseCase.authorizeRead` 를 `ErrorCaseAccess.requireRead` 로 일원화(중복 제거) · `ErrorCaseSummary.visibility`) · 인프라(`ErrorCaseEntity.visibility` + `ix_error_case_visibility` · adapter 매핑) · 표현(`CreateErrorCaseRequest` default PUBLIC · `UpdateErrorCaseRequest` nullable · `ErrorCaseDetailResponse`/`ErrorCaseSummaryResponse` 노출 · controller 매핑) · DB(`ALTER TABLE error_case ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC'` + 기존 25 row 백필: workspaceId 있으면 WORKSPACE(7건)/없으면 PUBLIC(18건) + CHECK + index). 댓글 코드 변경 0 — `requireRead` 단일 진입점 효과. 빌드(`./gradlew compileKotlin`) 통과.

**채택하지 않은 안**: PUBLIC 비로그인 허용 (JWT 정책 충돌) · 목록 정책에 PUBLIC 자동 노출 (검색/insight-api §6 도입과 함께 일관 처리 — 부분 일관성 회피) · 워크스페이스 이동 (범위 밖).

문서: `revisitable-decisions.md` §9 신설(결정 8개 + 채택하지 않은 안 + 후속 작업 4개) · `comment-discussion.md` §6 권한 매트릭스 + §8.5 권한 누수 갱신 · `comment-implementation.md` §9.3 권한 매트릭스 + §10 외부 의존 갱신 · `errorcase-domain.md` §1.1 ErrorCase 필드 + §6 권한 모델 표 갱신.

## 2026-06-01 18:26:51 KST · core-api · docs: 댓글 시스템 구현 가이드 신설 (comment-implementation.md)

**요청 한 줄**: 댓글 시스템 설계·구현·각 클래스 기능을 한 눈에 보는 별도 문서 작성.

**한 줄 요약**: `core-api/docs/comment-implementation.md` 신설 — 댓글 시스템의 *구현 관점* 종합 가이드. 11개 절: TL;DR(모듈 트리·호출 흐름 ASCII), 전체 디렉토리 구조(30 파일), 도메인 5 모델 + 3 VO 책임·메서드·invariants 표, 응용 1 포트 + 8 유스케이스 카탈로그(입력/책임/의존), 예외·Command 객체, 인프라 5 엔티티(table·UNIQUE·인덱스) + 5 JPA repo + 1 어댑터, 표현 8 endpoints + Request/Response DTO + 매퍼, 3 핵심 시나리오 데이터 흐름(댓글 작성/목록 N+1 회피/제안 상태 변경), DB 테이블 매핑(comment+4 부수), 호출 그래프(presentation→application→infrastructure), 함정·운영 노트 7개(bulk @Modifying 순서·N+1 회피·권한 매트릭스·sourceId opaque·diff lines 단순성·케이스 삭제 cascade·mention 식별자), 외부 의존(case/step/shared — snippet 의존 없음). `comment-discussion.md` §11 참고에 cross-link 추가, `errorcase-domain.md` §10 cross-link 갱신.

코드 변경 없음(문서만).

## 2026-06-01 18:11:38 KST · core-api · feat: 댓글 Diff 제안 (GitHub Suggest 차용) — comment/suggestion sub-aggregate 신설

**요청 한 줄**: 코드블럭 gutter 드래그로 GitHub 스타일 diff 제안 댓글 작성. HTML 시안에 정의된 새 기능 백엔드 구현.

**한 줄 요약**: 코드블럭의 라인번호(gutter) 영역을 드래그해 라인 범위 + after 코드를 첨부한 **Diff 제안 댓글**을 작성하는 기능을 백엔드에 추가. 댓글 1:1 `CommentSuggestion` 신규 도메인. **변경 파일 15**: 도메인(`CommentSuggestion.kt` + enum `SuggestionSourceType` SNIPPET/MARKDOWN_CODE / `SuggestionStatus` PENDING/APPLIED/REJECTED) · 응용(`CommentRepositoryPort` suggestion 메서드 4개 추가, `CreateCommentCommand` 에 SuggestionInput 옵션 + `UpdateSuggestionStatusCommand` 신규, `CreateCommentUseCase` suggestion 동시 저장, `DeleteCommentUseCase` cascade 정리, `ListCommentsUseCase` batch fetch + node 에 suggestion 포함, `UpdateSuggestionStatusUseCase` 신규 — 케이스 owner 만) · 인프라(`CommentSuggestionEntity` UNIQUE(commentId) + index source·status, `CommentSuggestionJpaRepository`, `CommentRepositoryAdapter` 확장) · 표현(`CreateCommentRequest.SuggestionRequest` + `UpdateSuggestionStatusRequest`, `CommentResponse.SuggestionDto` + `DiffLineDto`, `SuggestionStatusResponse`, `CommentSuggestion.toDto()` 가 단순 line-단위 unified diff lines 생성 — HTML buildDiff 동일 패턴, Controller 에 `PATCH /comments/{id}/suggestion/status` 신설 + 기존 응답에 suggestion 포함). 빌드(`./gradlew compileKotlin`) 통과.

**핵심 결정**: (a) **sourceId opaque** — BE 검증 X. *제안이지 반영 보장 아님* — snippet→comment 의존성 다시 들이지 않음 (직전 CODE_BLOCK 흡수 결정과 일관). (b) **상태 변경은 케이스 owner 만** (GitHub PR author 와 동치). 댓글 작성자는 PENDING 으로 제안만. (c) **자동 코드 반영 X (Phase 1)** — APPLIED 표시뿐, 실제 코드 변경은 작성자 수동. (d) **diff lines BE 생성** — 단순 line-단위 (old 전체 del + new 전체 add). 진짜 LCS 는 Phase 2. (e) **댓글 1:1** (UNIQUE commentId) — 여러 제안 묶음은 Phase 2. (f) **comment soft delete 시 suggestion 하드 삭제** — 함정 패턴(bulk @Modifying+clearAutomatically 순서) 그대로 적용.

문서: `comment-discussion.md` §11 신설(UX 흐름·모델·정책·통신 시나리오·sourceType 별 의미·응답 예시·확장 포인트) + TL;DR/§1 모델/§2 정책/§5 API/§6 권한/§7 기능 인벤토리/§10 코드 인벤토리 갱신. `revisitable-decisions.md` §7 에 2026-06-01 기능 추가 메모. 새 테이블 `error_case_comment_suggestion` (ddl-auto=update 로 hibernate 자동 생성).

## 2026-06-01 15:58:48 KST · core-api · refactor: 댓글 인용 종류 3종으로 단순화 (CODE_BLOCK 흡수)

**요청 한 줄**: 인용 4종 중 CODE_BLOCK 은 본문(CASE_BODY)으로 흡수하고, 원문 보기/슬라이드는 FE 가 quoteSnapshot 으로 처리하게 단순화.

**한 줄 요약**: `QuoteSourceKind` enum 을 4종(NONE/CASE_BODY/STEP/**CODE_BLOCK**) → **3종**(NONE/CASE_BODY/STEP) 으로 좁힘. 본문에 임베드된 `@snippet(id)`·코드블록은 *본문의 일부* 로 본다. **DB 점검 결과 `CODE_BLOCK` row 0건** 이라 데이터 마이그레이션 없이 enum 좁힘. 변경 파일: 도메인(`QuoteSourceKind`/`Comment` 도큐), 응용(`CreateCommentUseCase.validateQuoteSource` 분기 제거 + `CodeSnippetRepositoryPort` 의존성 제거, `ListCommentsUseCase.QuoteKindFilter`/`QuoteSummary.snippets[]` 제거), 표현(`CommentController` Swagger description·`QuoteSummaryDto.snippets` 제거·`CommentRequests` Swagger). **부수 정리**: `CodeSnippetRepositoryPort.findById(Long)` + adapter 구현 **롤백 제거** (오직 댓글 인용 검증을 위해 추가했던 것, 다른 사용처 없음). 빌드(`./gradlew compileKotlin`) 통과. 문서: `comment-discussion.md` (§0 TL;DR · §1 데이터모델 · §2 정책 매트릭스 · §3 FE 역할(원문 보기 slide 추가) · §4.2 시나리오(CASE_BODY snapshot+JS 점프 코드) · §5 응답 JSON · §7 기능 인벤토리 · §8.2 Flyway 마이그레이션 메모 · §9 확장 포인트 · §9-B 흡수 결정 등재 · §10 코드 인벤토리 변경) / `revisitable-decisions.md` §7 헤더 갱신 + 2026-06-01 변경 메모 + §8 결합 표 정정.

얻는 것: enum 1개·검증 분기 1개·port 메서드 1개·DB CHECK 값 1개·filter 옵션 1개 제거 + 모듈 의존 그래프 단순화(comment→snippet 의존 끊김). 잃는 것: `quoteSummary.snippets[]` 카운트(작음 — STEP 카운트가 더 의미 있는 시그널).

## 2026-05-31 22:08:11 KST · core-api · docs: 댓글 BC 위치 결정 등재 (revisitable-decisions §8 + comment-discussion §9-A)

**요청 한 줄**: 마이크로서비스/DDD 관점에서 comment 가 core-api(errorcase BC) 안에 있는 게 적합한지 분석·등재.

**한 줄 요약**: `revisitable-decisions.md` §8 신설 — "댓글 BC 위치: errorcase sub-module vs 별도 BC vs 별도 서비스". DDD 5 잣대 평가(유비쿼터스 언어·모델 결합·트랜잭션 경계·사용자 여정·변경축/재사용성) → 4/5 같은 BC 신호, 변경 축·재사용성 2개만 약한 다른 BC 신호 → **현재 위치 유지**. §검색/§매칭(derived read model) 과의 비교 표로 *comment 만 진짜 애그리거트* 임을 명시 — 분리한다면 별도 BC 가 자연이지만 *지금은 과설계*. 분리 옵션 A/B/C 3가지(현재 sub-module / 같은 서비스 안 별도 BC `core-api/discussion/` / 별도 마이크로서비스 `discussion-api`) + 분리 트리거 5개(다른 도메인 댓글 필요·모더레이션 비대화·트래픽 비대칭·noti 결합 폭증·외부 컨슈머) 등재. 분리 시 풀어야 할 결합(인용 검증·권한·cascade·`isAuthorOfCase`) 와 풀이 방법(ACL/이벤트) 명시. **현재 코드가 이미 분리 친화**(포트 인터페이스 의존·`ErrorCaseAccess` 공유 객체·`errorCaseId` 가 그냥 Long) 라 트리거 도달 시 폴더 이동 + ACL 1-2시간 분리 가능. `comment-discussion.md` 에 §9-A 요약 절 추가하고 §8 cross-link.

코드 변경 없음(문서만).

## 2026-05-31 21:54:25 KST · core-api · docs: 댓글 시스템 종합 문서 신설 (comment-discussion.md)

**요청 한 줄**: 확정된 댓글 모델·FE/BE 역할·통신 흐름·구현된 기능을 한 문서로 정리.

**한 줄 요약**: `core-api/docs/comment-discussion.md` 추가 (attachment-lifecycle.md 와 같은 스타일). TL;DR + 데이터 모델(Comment/Reaction/Helpful/Mention 4 테이블) + 확정 정책 매트릭스 + **FE/BE 역할 분담 표** + **통신 흐름 시나리오 8개**(일반 작성·드래그 인용·답글 depth=1 redirect·리액션·도움됨 popover·수정·삭제·목록·공유) + API 엔드포인트 + 권한 매트릭스 + 기능 인벤토리 + 함정·운영 노트 + 확장 포인트 7개 + 코드 인벤토리. `errorcase-domain.md` §10 cross-link 를 "구현 완료" 로 갱신, `revisitable-decisions.md` §7 상태도 같이 갱신(대안 회고만 §7 에 남음).

코드 변경 없음(문서만).

## 2026-05-31 21:51:37 KST · core-api · feat: 댓글 시스템 구현 (errorcase/comment 신설)

**요청 한 줄**: 확정된 댓글 모델을 백엔드 코드로 구현.

**한 줄 요약**: `errorcase/comment/` sub-module 신설. Comment + Reaction + Helpful + Mention 4 도메인. 인용 3종(CASE_BODY/STEP/CODE_BLOCK · NONE 일반) · **depth=1 답글 서버측 redirect** · **Slack 스타일 이모지 리액션**(UNIQUE) · **도움됨 모든 사용자 토글** · soft delete + placeholder · 편집됨 표시 · @멘션 적재. 7개 엔드포인트 + Swagger.

### 엔드포인트
```
POST   /api/v1/error-cases/{caseId}/comments                              생성
GET    /api/v1/error-cases/{caseId}/comments?sort=&quoteKind=&cursor=&size=  목록(트리+quoteSummary)
PATCH  /api/v1/error-cases/{caseId}/comments/{commentId}                  본인만 수정 (editedAt)
DELETE /api/v1/error-cases/{caseId}/comments/{commentId}                  본인만 soft delete
POST   /api/v1/error-cases/{caseId}/comments/{commentId}/helpful          누구나 토글
POST   /api/v1/error-cases/{caseId}/comments/{commentId}/reactions        이모지 추가(Slack 멱등)
DELETE /api/v1/error-cases/{caseId}/comments/{commentId}/reactions/{emoji} 본인 리액션 해제
```

### 정책 (확정)
- 인용 4종: `NONE` / `CASE_BODY` / `STEP`(stepId 검증) / `CODE_BLOCK`(snippetId 검증). SIGNATURE 미채택.
- 인용 없는 댓글은 응답에서 `quote=null` — 프런트가 뱃지 비노출.
- `quoteSnapshot` 500자 자동 자름(도메인 `Comment.truncateSnapshot`).
- **답글 depth=1**: 답글의 답글이면 서버가 *부모의 부모(top-level)* 로 redirect.
- 역할 배지: `isAuthorOfCase` boolean 만(작성자만 표시; 기여자/해결자/SIGNATURE 미채택).
- 이모지 리액션: UNIQUE(commentId, userId, emoji). 같은 사용자가 또 누르면 멱등.
- 도움됨: UNIQUE(commentId, userId). **모든 사용자가 토글 가능** (케이스 read 권한).
- 정렬: NEWEST(기본) / OLDEST / HELPFUL. HELPFUL 은 cursor 미지원.
- 필터: ALL / GENERAL / CASE_BODY / STEP / CODE_BLOCK.
- 응답 트리: top-level + `replies[]` (depth=1 children). 답글은 createdAt ASC.
- `quoteSummary` 응답 metadata 에 포함 — 사이드 패널 카운트 단일 요청.

### 변경 파일
- **추가** `comment/domain/model/{Comment, CommentReaction, CommentHelpful, CommentMention}.kt`, `vo/QuoteSourceKind.kt`.
- **추가** `comment/infrastructure/jpa/entity/{Comment, CommentReaction, CommentHelpful, CommentMention}Entity.kt`.
- **추가** `comment/application/port/CommentRepositoryPort.kt` (단일 포트에 4 도메인 묶음 — `ErrorCaseRepositoryPort` 가 snapshot/snippets 묶는 패턴과 일관).
- **추가** `comment/infrastructure/jpa/{Comment, CommentReaction, CommentHelpful, CommentMention}JpaRepository.kt` + `adapter/CommentRepositoryAdapter.kt`.
- **추가** `comment/application/command/CommentCommands.kt`.
- **추가** `comment/application/usecase/{CreateComment, UpdateComment, DeleteComment, ListComments, ToggleHelpful, AddReaction, RemoveReaction}UseCase.kt` + 예외 3종.
- **추가** `comment/presentation/web/CommentController.kt` + request/response DTO.
- **수정** `snippet/application/port/CodeSnippetRepositoryPort` + adapter — `findById(Long)` 추가(인용 검증용).
- **수정** `case/application/usecase/DeleteErrorCaseUseCase` — 댓글/reactions/helpful/mentions cascade 정리(자식 먼저).

### 함정 기록
**DeleteCommentUseCase**: `softDelete()` 후 save 호출을 `deleteAllReactions/Helpful/Mentions` 보다 *앞에* 두면, bulk `@Modifying(clearAutomatically=true)` 가 persistence context 를 비워 status UPDATE 가 사라진다(회원 탈퇴 때 잡았던 동일 함정). **정리 → save 마지막** 순서로 고정.

### 검증 (core-api 직접, internal JWT, case owner 777)
- 일반(NONE) 댓글 ✓ · STEP 인용 ✓
- 답글 (parent=C2) → parentCommentId=C2 ✓
- **답글의 답글 → 서버가 parent 를 C2(top-level)로 redirect** ✓
- 리액션 추가 → count=1, 같은 사용자 재호출 멱등 → count=1, 해제 → count=0 ✓
- 도움됨 토글 on → count=1, 또 호출 → count=0 ✓
- 본인 수정 → `editedAt` 세팅 ✓
- soft delete → body=`(삭제된 본문)`, `deletedAt` 세팅 ✓
- list 트리 응답 ✓ (top-level 2건 + replies 2건 그루핑, deleted=true placeholder)
- filter=STEP → STEP 인용 댓글만 1건 ✓
- quoteSummary {general:2, steps:[(7,1)]} ✓
- 존재 X step 인용 시도 → 400 ✓
- Swagger 7개 엔드포인트 노출 ✓

### 운영(Flyway) 절차
신규 테이블 4개: `error_case_comment`, `error_case_comment_reaction`(UNIQUE commentId+userId+emoji), `error_case_comment_helpful`(UNIQUE commentId+userId), `error_case_comment_mention`. dev `ddl-auto: update` 로 자동 생성, 운영은 Flyway 명시. enum CHECK 추가:
```sql
ALTER TABLE error_case_comment ADD CONSTRAINT error_case_comment_quote_source_kind_check
  CHECK (quote_source_kind IN ('NONE','CASE_BODY','STEP','CODE_BLOCK'));
```

## 2026-05-31 15:07:35 KST · core-api · docs: 댓글 시스템 설계 등재 (revisitable-decisions §7)

**요청 한 줄**: 합의된 댓글 모델을 구현 전에 문서로 정리.

**한 줄 요약**: `core-api/docs/revisitable-decisions.md` §7 "댓글(Comment) 시스템" 신설. 데이터 모델(Comment + Reaction + Mention) · 정책 매트릭스(인용 자동화·depth=1 답글·도움됨 ✅·역할 배지·인용 snapshot·soft delete) · 엔드포인트 트리 · 트리 응답 형태 · 채택 안 한 대안(step.comments / depth=∞ / depth=0 / accepted answer 단일) · 함정 + 재검토 트리거 · 확장 포인트(알림·매칭 신호·댓글→step 승격). `errorcase-domain.md` §10 확장 포인트 목록에 cross-link.

코드 변경 없음(문서만). 구현 PR 은 별도 — 사용자가 보내줄 HTML 분석 후 모델 보강 검토 뒤 진행.

## 2026-05-29 21:30:03 KST · multi · feat: Settings/Profile IA Phase 1 — User preferences + 공개 프로필 + OAuth 연결 조회

**요청 한 줄**: IA 결정 등재 + Phase 1 백엔드 구현.

**한 줄 요약**: `revisitable-decisions.md` §5 에 IA 결정 등재(공개 프로필↔사적 Settings 분리·7탭·알림 매트릭스·Phase 1~5). iam-api 의 `User` 도메인에 **language/timezone/theme/defaultWorkspaceId** 4필드, `GET/PATCH /users/me` 확장, **`GET /users/{id}` 공개 프로필**, **`GET /users/me/connections` OAuth 연결 조회** 신설.

### 추가/확장된 엔드포인트
```
GET    /api/v1/users/me                 ← 확장 (preferences 4필드 포함)
PATCH  /api/v1/users/me                 ← 확장 (preferences + clear* 플래그)
GET    /api/v1/users/{userId}           ← 신설 (공개 프로필, 비공개 필드 미노출)
GET    /api/v1/users/me/connections     ← 신설 (OAuth 연결 목록)
```

### 변경 파일
- **추가** `iam-api/auth/domain/model/vo/Theme.kt` (LIGHT/DARK/SYSTEM).
- **수정** `iam-api/auth/domain/model/User.kt` — preferences 4필드, `changeLanguage`/`changeTimezone`/`changeTheme`/`changeDefaultWorkspace`, validate(BCP 47 / IANA tz), `finalizeDeletion` 도 4필드 초기화.
- **수정** `iam-api/auth/infrastructure/jpa/entity/UserEntity.kt` — 4컬럼 + 매핑.
- **수정** `iam-api/auth/application/command/UpdateMyProfileCommand.kt` — 4필드 + 3 clear 플래그.
- **수정** `iam-api/auth/application/usecase/UpdateMyProfileUseCase.kt`, `GetMyProfileUseCase.kt` — Result 확장.
- **수정** `iam-api/auth/presentation/web/dto/request/UpdateMyProfileRequest.kt`, `dto/response/MyProfileResponse.kt` — preferences 반영.
- **추가** `auth/application/usecase/GetPublicProfileUseCase.kt`, `ListMyConnectionsUseCase.kt`.
- **추가** `auth/presentation/web/dto/response/PublicProfileResponse.kt`, `ConnectionsResponse.kt`.
- **수정** `auth/application/port/SocialIdentityRepositoryPort.kt`(`findAllByUserId`), `infrastructure/jpa/SocialIdentityJpaRepository.kt`, `adapter/SocialIdentityRepositoryAdapter.kt`.
- **수정** `auth/presentation/web/UserController.kt` — 4 endpoint 변경/추가 + Swagger 동시(메모리 규칙) + IAE→400 매핑.
- **수정** `iam-api/build.gradle.kts` — **`tools.jackson.module:jackson-module-kotlin`** 추가(Boot 4/Jackson 3 환경에서 Kotlin data class default value 처리; 누락 시 boolean primitive 직렬화 NPE).

### 함정 기록
1. `theme` 컬럼은 `NOT NULL` 이라 ddl-auto=update 가 기존 row 때문에 자동 추가 못 함. 로컬에서 수동 ALTER TABLE 로 컬럼 추가 + `SYSTEM` 백필 + CHECK 적용. 운영은 Flyway:
   ```sql
   ALTER TABLE iam_user ADD COLUMN theme VARCHAR(16);
   UPDATE iam_user SET theme = 'SYSTEM' WHERE theme IS NULL;
   ALTER TABLE iam_user ALTER COLUMN theme SET NOT NULL;
   ALTER TABLE iam_user ADD CONSTRAINT iam_user_theme_check CHECK (theme IN ('LIGHT','DARK','SYSTEM'));
   ALTER TABLE iam_user ADD COLUMN language VARCHAR(16);
   ALTER TABLE iam_user ADD COLUMN timezone VARCHAR(64);
   ALTER TABLE iam_user ADD COLUMN default_workspace_id BIGINT;
   ```
2. **iam-api 가 jackson-kotlin 모듈 없이 동작**해 왔던 게 운 좋은 우연. PATCH 에 `Boolean = false` default 가 있는 DTO 가 들어오면 `Cannot map null into type boolean` 으로 400. core-api 와 동일한 의존성 추가로 해결.

### 검증 (iam-api 직접, internal JWT)
- `GET /me` 응답에 preferences 4필드 포함 ✓
- `PATCH {language:"ko", timezone:"Asia/Seoul", theme:"DARK", defaultWorkspaceId:3}` → 반영 ✓
- `PATCH {clearTimezone:true}` → timezone=null, 다른 필드 유지 ✓
- 잘못된 timezone(`Mars/Olympus`) → 400 ✓
- `GET /users/3` 공개 프로필 — 비공개 필드(email/preferences/pendingDeletionAt) 응답에 없음 ✓ (keys: avatarUrl/bio/displayName/isDeleted/status/userId)
- `GET /users/me/connections` → GitHub 연결 1건 반환 (provider/email/profileUrl/linkedAt, providerUserId 미노출) ✓
- Swagger 신규 2개 + 확장 2개 정상 노출 ✓

> IA 결정은 `core-api/docs/revisitable-decisions.md` §5 등재. Phase 2(Privacy 탭) 부터는 검색/visibility 도입 시점과 묶어 진행 예정.

## 2026-05-29 15:22:08 KST · core-api · refactor: errorcase BC 를 6 aggregate sub-module 로 분리 (case/step/solution/snippet/attachment/shared)

**요청 한 줄**: 한 BC 안에서 애그리거트별로 sub-module 분리.

**한 줄 요약**: `errorcase/` 패키지 평탄 구조를 **6개 sub-module**(`case/`, `step/`, `solution/`, `snippet/`, `attachment/`, `shared/`)로 재배치. 각 sub-module 안에서 `domain/application/presentation/infrastructure` 4계층 골격 유지. **외부 계약(엔드포인트·DB 스키마·HTTP 응답) 변경 0**, 내부 패키지 경로만 변경.

### 결과
- 파일 이동: **98 → 6 sub-module**.
- 분리된 mixed 파일: `StepCommands`(→ step/+solution/), `StepRequests`/`StepResponses`(→ step/+solution/) 3개.
- 패키지 선언 갱신: 88개 파일.
- import 일괄 갱신: **232 substitutions** across 64 files (core-api + iam-api + gateway + shared-internal-auth scan, errorcase 외부 의존 import 변경).
- 추가된 import: **33개** (같은 패키지였던 cross-aggregate 클래스가 분리되어 명시 import 필요).

### 재배치 매핑 (요약)
- **case/** — ErrorCase 도메인(+VO Status/Snapshot/Fingerprint/Meta/Severity/RawStackTrace) · 생성·조회·목록·수정·삭제 유스케이스 · 컨트롤러 · 본인 DTO · 엔티티/Embeddable · 추출 어댑터.
- **step/** — Step 도메인(+VO StepStatus/AttemptType) · CRUD 유스케이스 · 컨트롤러 · DTO · 엔티티.
- **solution/** — Solution 도메인 · CRUD 유스케이스 · 컨트롤러 · DTO · 엔티티(`@OrderColumn` join 포함).
- **snippet/** — CodeSnippet(이전 vo→aggregate 승격) · 생성/수정/삭제/조회 유스케이스 · GC 스케줄러 · 컨트롤러 · DTO · 엔티티 · `SnippetProperties`.
- **attachment/** — Attachment(이전 vo→aggregate 승격) · AttachmentKind · 업로드/다운로드/삭제 · GC 스케줄러 · 로컬 스토리지 어댑터 · 컨트롤러 · DTO · 엔티티 · `AttachmentStorageProperties`.
- **shared/** — `ErrorCaseAccess`(권한 헬퍼) · `WorkspaceQueryPort`+IAM 어댑터 · `MarkerIdGeneratorPort`+adapter · `IamApiProperties`.

### 자동화 (Python + sed)
1. `git mv` (tracked 파일) + plain `mv` (untracked 파일) 일괄. 빈 옛 디렉토리 정리.
2. 파일 디렉토리 경로 → 새 `package` 선언 자동 생성 (88 파일).
3. **심볼 → 새 패키지 매핑** 추출 (125 심볼). 충돌 0.
4. 정규식 `org\.studieojavry\.coreapi\.errorcase(?:\.[a-z]+)+\.SYMBOL` 매칭으로 옛 FQN/import 를 새 FQN 으로 일괄 치환.
5. 같은 패키지였던 cross-aggregate 참조에 명시 import 자동 추가.

### 동작 검증
- core-api 컴파일 → 성공.
- 재기동 → 8081 listening.
- e2e smoke: 케이스 생성 → step 추가(IN_PROGRESS 자동) → SUCCESS step(suggestResolve=true) → solution([s1,s2]) 묶기 → ✅ 전부 통과.
- Swagger 엔드포인트 목록 변화 없음 (계약 보존 확인).

### 변경된 외부 효과
- 외부 API 계약 / DB 스키마 / 응답 본문 — 변경 없음.
- iam-api/gateway 코드의 import 일부 갱신 (errorcase 내부 클래스 참조).
- IDE 의 패키지 트리만 6개로 갈라짐.

### 문서 갱신
- `core-api/CLAUDE.md` 아키텍처 절 — 6 sub-module 트리로 갱신, cross-aggregate 의존성 정책(`shared` 경유) 명시.
- `errorcase-domain.md`/`revisitable-decisions.md`/`attachment-lifecycle.md` 의 코드 경로 인용은 후속 작업 필요(주된 의미는 변함 없음).

> 동기: 한 BC 안의 평탄 구조가 step/solution 도메인 추가로 100개 가까운 파일로 커져 가독성·탐색성·향후 BC 분리 가능성 모두 악화. DDD 의 "한 BC, 여러 애그리거트" 정석에 맞춤. BC 자체 분리 트리거는 `revisitable-decisions.md` §4 의 4가지 신호 도달 시 재논의.

## 2026-05-29 14:51:27 KST · core-api · docs: 재검토 결정 문서에 "Solution 템플릿화/재사용/매칭" 분석 추가

**요청 한 줄**: 사용자가 만든 solution 을 템플릿화해 다른 사용자가 재사용하는 방향을 분석. 구현 전 설계 결정만 문서에 트래킹.

**한 줄 요약**: `core-api/docs/revisitable-decisions.md` 에 항목 §4 "Solution 의 템플릿화 / 재사용 / 매칭" 추가. (a) 내 재사용 / (b) 팀 공유 / (c) 공개 카탈로그 / (d) 자동 매칭 4갈래 의미 정리 + 매칭 키 비교(fingerprint/exceptionClass/태그/FTS/임베딩) + 데이터 모델 옵션(A 참조 / B 인스턴스 복제 / C SolutionTemplate / D 케이스=템플릿) + 가시성 정책 + **점진 도입 4단계**(Phase 1 자동매칭+참조 → Phase 2 복제 → Phase 3 SolutionTemplate → Phase 4 시맨틱) + 풀어야 할 어려움(컨텍스트 잡음·cross-case 공유·권한 누수·중복 큐레이션·버전 표류) + 단계별 산출물 + 재검토 트리거 + 활용 가능/추후 추가 코드. `errorcase-domain.md` §10 확장 포인트에 cross-link.

코드 변경 없음(문서만). 사용자 트래픽 누적 후 Phase 1 부터 재논의.

## 2026-05-28 19:54:05 KST · core-api · docs: 에러케이스 · 스텝 · 솔루션 도메인 통합 설계 문서 신설

**요청 한 줄**: 구현한 에러케이스/스텝/솔루션의 정책과 구현 상세를 한 문서로 정리.

**한 줄 요약**: `core-api/docs/errorcase-domain.md` 추가. TL;DR + 도메인 모델 표(ErrorCase/Step/Solution) + 상태 전이 다이어그램·표 + Step 본문 D방식+옵트인 템플릿 트레이드오프 분석 + 자동 전이/RESOLVED 추천 시퀀스 + Solution 정책(N개·순서·검증·참조보호) + 권한 모델(ErrorCaseAccess) + cascade 순서(solution→step→attachment→snippet→case) + 함정·운영 노트(IAE→400, ddl-auto vs Flyway, status var화, scope 미검증) + 엔드포인트 요약 + 확장 포인트(재정렬·solution stepIds 재구성·step 본문 전용 marker 등) + 코드 인벤토리.

코드 변경 없음(문서만). 관련: `attachment-lifecycle.md`, `revisitable-decisions.md`.

## 2026-05-28 19:33:20 KST · core-api · feat: Step / Solution 도메인 — 해결 시도 타임라인 + 해결 방법 묶음

**요청 한 줄**: 에러케이스에 해결 시도(step)를 타임라인으로 쌓고, step 조합을 solution 으로 묶어 등록.

**한 줄 요약**: `Step`(케이스당 N개, 타임라인) + `Solution`(케이스당 N개, step 조합 묶음) 도메인 신규. 본문은 **메타 분리 + 자유 마크다운**(D방식, 사용자 선택). 첫 step 추가 시 케이스 OPEN→IN_PROGRESS 자동 전환, SUCCESS step + IN_PROGRESS 면 응답에 `suggestResolve=true` hint → SPA 가 모달로 묻고 `PATCH /error-cases/{id} { status:"RESOLVED" }` 호출.

### 엔드포인트
- `POST   /api/v1/error-cases/{caseId}/steps` — step 추가 + 자동 전이 + suggestResolve hint
- `GET    /api/v1/error-cases/{caseId}/steps`
- `PATCH  /api/v1/error-cases/{caseId}/steps/{stepId}` — 작성자 본인만
- `DELETE /api/v1/error-cases/{caseId}/steps/{stepId}` — 참조 solution 있으면 409
- `POST   /api/v1/error-cases/{caseId}/solutions`
- `GET    /api/v1/error-cases/{caseId}/solutions`
- `DELETE /api/v1/error-cases/{caseId}/solutions/{solutionId}` — 작성자 또는 케이스 소유자
- `PATCH  /api/v1/error-cases/{id}` 에 `status` 필드 추가(수동 전이)

### Step 구조 (D방식: 메타 분리 + 자유 본문)
| 필드 | 타입 | 설명 |
|---|---|---|
| `title` | String ≤200 | 타임라인 카드 헤드라인 |
| `status` | `SUCCESS` / `PARTIAL` / `FAILURE` | 색상·아이콘 + RESOLVED 트리거 |
| `insight` | String ≤500 | 한두 문장 요약. 카드에 항상 표시(SUCCESS=학습, FAILURE=원인) |
| `attemptType` | enum (선택) | `CODE_CHANGE`/`CONFIG`/`DEPENDENCY`/`ENV`/`ROLLBACK`/`INVESTIGATION`/`OTHER` — 필터/뱃지 |
| `body` | TEXT (선택) | 자유 마크다운, `@snippet(...)`/`@attach(...)` 임베드 가능, 비어도 OK |

옵트인 템플릿 헤더(`## 시도/결과/학습`)는 프런트 책임 — 백엔드는 자유 본문 그대로 저장.

### 자동 상태 전이 + RESOLVED 추천
- 케이스 `OPEN` + 첫 step → `IN_PROGRESS` 자동 전환(같은 트랜잭션).
- step.status=`SUCCESS` + 케이스가 `IN_PROGRESS` → 응답 `suggestResolve=true` hint.
- 실제 `RESOLVED` 전환은 사용자 명시 액션(`PATCH /error-cases/{id} { status:"RESOLVED" }`). 도메인 `ErrorCase.transitionTo()` 가 전이 규칙 검증(잘못된 전이 → IAE → 400).

### Solution
- step N개를 묶어 한 문장 제목(`title`)으로 요약. `@OrderColumn` 으로 사용자 선택 순서 보존.
- 검증: stepIds 모두 같은 케이스의 step (타 케이스 stepId → 400), 중복 금지, 빈 배열 금지.
- step 삭제는 참조 solution 있으면 409 — 사용자가 solution 먼저 정리하게 명시.

### 변경 파일 (추가)
- 도메인: `domain/model/Step`, `Solution`, `vo/StepStatus`, `vo/AttemptType`. `ErrorCase.status` var 화 + `transitionTo()`.
- 인프라: `infrastructure/jpa/entity/StepEntity`(`error_case_step`), `SolutionEntity`(`error_case_solution` + `error_case_solution_step` join table, `@OrderColumn`).
- 포트/어댑터: `StepRepositoryPort`/`SolutionRepositoryPort` + JPA + 어댑터.
- 응용: `CreateStepUseCase`(자동 전이), `ListSteps`/`UpdateStep`/`DeleteStep`, `CreateSolutionUseCase`(stepIds 검증), `List`/`DeleteSolutionUseCase`, `ErrorCaseAccess`(공통 읽기/쓰기 권한 헬퍼).
- 표현: `StepController`, `SolutionController`, request/response DTO. `UpdateErrorCaseRequest.status` 추가, controller catch 에 IAE→400.
- cascade: `DeleteErrorCaseUseCase` 가 step·solution 도 함께 정리(solution 먼저 → step).

### 검증 (core-api 직접, internal JWT, 케이스 24)
- 첫 step(FAILURE) → caseStatus=IN_PROGRESS, suggestResolve=false ✓
- SUCCESS step → suggestResolve=**true** ✓
- 사용자 확인 후 `PATCH { status:RESOLVED }` → caseStatus=RESOLVED ✓
- Solution(stepIds=[2,3]) 생성 + 순서 보존 ✓
- 타 케이스 step 으로 solution 시도 → 400 ✓
- solution 이 참조 중인 step 삭제 → 409 ✓
- 비소유자 step 추가 → 403 ✓
- Swagger 7개 신규 엔드포인트 노출 ✓

### 운영(Flyway)
스키마 자동 추가: `error_case_step`, `error_case_solution`, `error_case_solution_step`(join). dev `ddl-auto=update`로 자동 생성됨. 운영은 Flyway 마이그레이션 필요(컬럼 길이·인덱스 명시).

## 2026-05-28 18:40:43 KST · iam-api · docs: 회원 탈퇴 라이프사이클·정책 문서 신설

**요청 한 줄**: 회원 탈퇴 시스템의 정책·구현 상세 설계를 한 문서에 기록.

**한 줄 요약**: `iam-api/docs/account-deletion-lifecycle.md` 추가. TL;DR + 상태 모델 + 단계별 동작(Request/Restore/Finalize) + 정책 결정(채택안·대안) + 자동 ADMIN 승격 규칙 + cross-cutting 영향 매트릭스 + bulk @Modifying 함정 기록 + 운영 절차(Flyway·ShedLock·설정·모니터링) + 확장 포인트(UserDeleted 이벤트·휴지통 UI·GDPR export) + 관련 코드 인벤토리. 첨부의 `attachment-lifecycle.md` 와 같은 스타일.

코드 변경 없음(문서만).

## 2026-05-28 18:38:20 KST · iam-api · feat: 회원 탈퇴 — soft delete + 30일 grace + 자동 ADMIN 승격

**요청 한 줄**: 회원 탈퇴 기능 신규 구현.

**한 줄 요약**: `DELETE /api/v1/users/me`(요청) + `POST /api/v1/users/me/restore`(복구). **soft delete + 30일 grace period** 모델: `ACTIVE → PENDING_DELETION → (30일 후 배치) → DELETED`. 사용자가 마지막 ADMIN 인 워크스페이스는 **자동으로 다음 멤버 ADMIN 승격**. core-api 의 owner/uploader user_id 는 그대로 유지(ghost).

### 라이프사이클
```
ACTIVE  ──DELETE /users/me──▶  PENDING_DELETION  ──30일 후 배치──▶  DELETED + PII 익명화
                ▲ restore           │
                └──POST /users/me/restore (grace 기간 내)
```
- **즉시(요청 시)**: 워크스페이스 멤버십 정리(자동 강등 포함), 팔로우 양방향 엣지 삭제, refresh 토큰 전부 폐기, refresh 쿠키 만료.
- **유지(grace 기간)**: 같은 OAuth 로 재로그인 시 SPA 가 `pendingDeletionAt` 을 보고 "복구하기" 노출.
- **확정(30일 후)**: status=DELETED + email/displayName/avatar/bio 익명화(`deleted_user_{id}`) + OAuth identity 삭제(같은 GitHub 계정 재가입 가능).

### 자동 ADMIN 승격 규칙
사용자가 ADMIN 이고 그 워크스페이스의 **마지막 ADMIN**일 때:
1. WRITE 멤버 중 가장 오래된 사람 → ADMIN.
2. WRITE 없으면 READ 멤버 중 가장 오래된 사람 → ADMIN.
3. 본인이 유일 멤버 → 워크스페이스도 hard-delete(콘텐츠는 core-api 가 보존).

### 변경 파일
- **수정** `auth/domain/model/vo/UserStatus` — `PENDING_DELETION` 추가, `canSignIn()` 이 ACTIVE+PENDING_DELETION 둘 다 허용(복구 결정용).
- **수정** `auth/domain/model/User` — `pendingDeletionAt` 필드 + `requestDeletion()`/`restore()`/`finalizeDeletion()` 메서드(상태전이·PII 익명화).
- **수정** `auth/infrastructure/jpa/entity/UserEntity` — `pending_deletion_at` 컬럼.
- **수정** `auth/application/port/UserRepositoryPort`(`findPendingDeletionBefore`), `SocialIdentityRepositoryPort`(`deleteByUserId`), `social/application/port/FollowRepositoryPort`(`deleteAllByUser`) + 어댑터·JPA 구현.
- **추가** `auth/application/usecase/RequestAccountDeletionUseCase` — 단일 트랜잭션에서 멤버십 정리·팔로우 삭제·refresh 폐기·status 전환(save 는 **반드시 마지막**: 함정 참조).
- **추가** `auth/application/usecase/RestoreAccountUseCase`, `FinalizeDeletedAccountsUseCase` (TransactionTemplate 으로 사용자별 독립 트랜잭션).
- **추가** `workspace/application/usecase/TransferMembershipsOnUserLeaveUseCase` — 자동 강등.
- **추가** `auth/config/AccountDeletionProperties` (`iam.account-deletion.grace-period=PT720H`), `AccountDeletionConfig`(`@EnableScheduling` + TransactionTemplate 빈), `infrastructure/schedule/AccountDeletionScheduler`(`@Scheduled` 1시간 간격).
- **수정** `presentation/web/UserController` — `DELETE /me`, `POST /me/restore` + Swagger 동시 작성(메모리 규칙).

### 함정 기록 — bulk @Modifying 의 컨텍스트 clear
초기 구현에서 `user.requestDeletion()`/`save()` 를 정리 작업 **전에** 호출했더니 status UPDATE 가 사라졌다. 원인: `WorkspaceMember`/`Follow`/`RefreshToken` 의 `@Modifying(clearAutomatically=true)` 가 **flushAutomatically=false(기본)** 라 pending UPDATE 를 flush 하지 않은 채 persistence context 를 clear → save 가 손실. 수정: usecase 에서 **정리 작업 먼저, user save 는 가장 마지막**.

### 검증 (iam-api 직접, internal JWT)
- A) user 7(ws5 READ): DELETE→204, Set-Cookie refresh 만료, status=PENDING_DELETION, pendingDeletionAt 세팅, ws5 멤버 정리, restore→ACTIVE ✓
- B) user 3(ws2 sole ADMIN, user 4 WRITE): DELETE→204, **ws2 의 user 4 가 ADMIN 으로 자동 승격**, user 3 빠짐 ✓

### 운영 절차 (Flyway)
- 컬럼 추가: `ALTER TABLE iam_user ADD COLUMN pending_deletion_at TIMESTAMP WITH TIME ZONE NULL;`
- CHECK constraint 갱신: `ALTER TABLE iam_user DROP CONSTRAINT iam_user_status_check; ALTER TABLE iam_user ADD CONSTRAINT iam_user_status_check CHECK (status IN ('ACTIVE','PENDING_DELETION','SUSPENDED','DELETED'));`
- 다중 인스턴스 배포 시 `AccountDeletionScheduler` 에 ShedLock 적용(core-api 의 orphan GC 패턴과 동일) — 현재 단일 인스턴스 가정.

## 2026-05-28 14:14:19 KST · multi · feat: OpenAPI/Swagger UI 전체 서비스로 확장 + gateway aggregator

**요청 한 줄**: "core 만 하지 말고 전체 API에 swagger 만들어달라" — iam-api 도 동일 작업 + gateway 에서 한 화면에 모음.

**한 줄 요약**: iam-api 의 7개 컨트롤러(25 엔드포인트)에 `@Operation`/`@ApiResponses`/`@Parameter`/`@Schema` 부착, `OpenApiConfig`(Bearer JWT 스킴) 도입. **gateway 가 aggregator UI** — dropdown 으로 iam-api/core-api 스펙 전환. publish/insight/noti 는 컨트롤러 0개라 작업 없음(스텁 명시).

### iam-api 변경
- **추가** `iam-api/build.gradle.kts` — `springdoc-openapi-starter-webmvc-ui:3.0.3`.
- **추가** `shared/config/OpenApiConfig` — Bearer JWT(`bearer-jwt`) 스킴, 서버 `localhost:8080`(직접) + `localhost:8000`(게이트웨이 경유), public 엔드포인트 안내.
- **수정** `auth/infrastructure/security/SecurityConfig` — `/v3/api-docs/**`·`/swagger-ui/**`·`/swagger-ui.html` permitAll.
- **수정** 7 컨트롤러(`Token`/`User`/`OAuth`/`Workspace`/`WorkspaceMember`/`WorkspaceInvitation`/`Follow`) — 각 메서드에 `@Operation`(언제·왜·전제) + `@ApiResponses`(성공·실패 코드+설명). public 엔드포인트(refresh/logout/OAuth/follow-status·followers·following/invitations.preview)는 `@SecurityRequirements` 로 lock 해제 → Swagger UI 에서 인증 없이 호출 가능.

### gateway 변경 (aggregator)
- **추가** `gateway/build.gradle.kts` — `springdoc-openapi-starter-webmvc-ui:3.0.3`.
- **수정** `config/RouteConfig.kt` — SCG MVC 라우트 2개 추가: `/api-docs/iam-api` → `localhost:8080/v3/api-docs`, `/api-docs/core-api` → `localhost:8081/v3/api-docs`. **same-origin 프록시로 CORS 회피**. 또한 core-api 라우트에 `/api/v1/error-snippets/**` 누락분 추가.
- **수정** `config/SecurityConfig.kt` — swagger 경로 + `/api-docs/**` permitAll.
- **수정** `application-local.yml` — `springdoc.swagger-ui.urls` 로 dropdown 두 entry. `disable-swagger-default-url: true`.

### 동작 확인
- **`http://localhost:8000/swagger-ui.html`** — gateway aggregator 진입(302 → `/swagger-ui/index.html`). 우상단 dropdown 에 **iam-api / core-api** 선택지.
- `/v3/api-docs/swagger-config` 가 `urls=[{name:core-api,url:/api-docs/core-api},{name:iam-api,url:/api-docs/iam-api}]` 반환.
- iam-api: `localhost:8080/swagger-ui` 직접도 가능. 25 엔드포인트, public/auth 자동 분리(lock 아이콘).
- core-api: `localhost:8081/swagger-ui` 직접 + aggregator 양쪽 동작. 11 operations / 6 paths.
- publish-api/insight-api/noti-api: `@RestController` 0개(빈 스텁) → 작업 없음.

### 함정·해결
- `springdoc.api-docs.enabled: false` 를 gateway 에 켜면 swagger-ui 부트스트랩까지 실패(401)했다. 제거. gateway 자체 스펙은 라우트만 있어 거의 비어있지만 어차피 dropdown 에서 다운스트림만 골라 쓰면 됨.
- 다운스트림 스펙을 절대 URL 로 직접 넣으면 CORS 차단 우려 → gateway 의 SCG 라우트로 same-origin 프록시(`setPath("/v3/api-docs")`)해 회피.

## 2026-05-28 13:33:58 KST · core-api · feat: OpenAPI/Swagger UI 도입 + 전체 컨트롤러/DTO 문서화

**요청 한 줄**: 현재 구현된 모든 API 를 Swagger UI 에서 성공/실패 시나리오까지 테스트 가능하게 문서화.

**한 줄 요약**: `springdoc-openapi-starter-webmvc-ui:3.0.3`(Spring Boot 4 + Jackson 3 호환) 도입. `OpenApiConfig` 빈으로 메타/`X-Internal-Auth` apiKey 보안 스킴 정의. 11개 엔드포인트 전체에 `@Operation`(언제·왜·전제) + `@ApiResponses`(성공·실패 코드와 예시 본문) + `@Parameter`, 주요 DTO 에 `@Schema`(설명·예시·제약) 부착.

### 동작 확인
- `http://localhost:8081/swagger-ui/index.html` — Swagger UI 로드(200).
- `http://localhost:8081/v3/api-docs` — OpenAPI 3 스펙 JSON(200). 11/11 엔드포인트 자동 감지, 3 태그(`error-cases`/`error-snippets`/`error-attachments`) 분류, 글로벌 `security: [{X-Internal-Auth: []}]` 적용.
- UI 우상단 **Authorize** → 토큰 입력(Bearer 접두사 없이 raw JWT). 토큰 발급: `scripts/mint-jwt.sh internal core-api <userId> USER` (수명 90초).

### 설계 결정
- **springdoc v3.x 사용**: Boot 4 + Jackson 3 호환은 springdoc v3 만 지원(v2 는 Boot 3/Jackson 2 까지). [참고: springdoc.org/v4, issue #3200]
- **글로벌 보안 스킴 + 엔드포인트별 override 없음**: 비즈니스 엔드포인트가 전부 internal JWT 필요 → 한 번에 묶음. `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` 은 `SecurityConfig` 에서 `permitAll` (UI 자체는 인증 없이 열람).
- **응답 예시는 ProblemDetail 형식**: `ResponseStatusException` 이 생성하는 본문 구조(`{type,title,status,detail,instance}`)를 그대로 예시로 사용 → 실제와 어긋남 없음.
- **multipart(첨부 업로드)**: 명시 schema 대신 `@RequestPart` 파라미터에 `@Parameter(description=...)` 만 달고 springdoc 자동 추론에 맡김.

### 변경 파일
- **수정** `core-api/build.gradle.kts` — `springdoc-openapi-starter-webmvc-ui:3.0.3` 추가.
- **추가** `shared/config/OpenApiConfig` — 메타/서버/X-Internal-Auth 스킴.
- **수정** `shared/config/SecurityConfig` — swagger 경로 permitAll.
- **수정** `presentation/web/ErrorCaseController`, `SnippetController`, `ErrorCaseAttachmentController` — `@Tag`/`@Operation`/`@ApiResponses`(예시 포함)/`@Parameter`.
- **수정** `presentation/.../CreateErrorCaseRequest`, `UpdateErrorCaseRequest`, `CodeSnippetCreateRequest`, `CodeSnippetUpdateRequest` — `@Schema`(description/example/min·max).

## 2026-05-27 23:59:26 KST · core-api · feat: 스니펫 내용 수정 엔드포인트

**요청 한 줄**: 스니펫 내용 수정 엔드포인트 추가.

**한 줄 요약**: `PATCH /api/v1/error-snippets/{markerId}` 추가. 업로더만, **markerId·uploadedBy·uploadedAt·errorCaseId(연결) 고정**한 채 `title/language/filePathOrClass/lineRange/caption/code` 부분 수정. markerId 가 유지되므로 본문 `@snippet(markerId)` 인라인 참조가 안 깨진다. 케이스에 연결된 스니펫도 수정 가능(연결=소속과 내용은 별개 책임).

### 설계
- **PATCH 시맨틱**: null=유지. `code`/`language` 는 보낼 경우 빈 문자열 금지(`@Size(min=1)` → 400). title 은 blank 면 기존 유지.
- **포트 변경 없음**: 어댑터 `CodeSnippetRepositoryAdapter.save` 가 이미 markerId 기준 upsert(내용 6필드만 갱신, uploadedBy/At·errorCaseId 보존)라 재사용.
- 삭제(DeleteSnippet)는 연결된 스니펫을 막지만, **수정은 허용** — 케이스에 보이는 코드를 그 자리에서 고치는 게 이 기능의 목적.

### 변경 파일
- **추가** `application/command/UpdateSnippetCommand`, `application/usecase/UpdateSnippetUseCase`(+ `SnippetAccessDeniedException`→403), `presentation/.../CodeSnippetUpdateRequest`, `presentation/.../CodeSnippetDetailResponse`(내용 포함 단건 응답).
- **수정** `presentation/web/SnippetController` — `PATCH /{markerId}` + 유스케이스 주입 + 예외 매핑(404/403).

### 검증 (core-api 직접, internal JWT)
code 수정 시 markerId·errorCaseId 유지 ✓ / 케이스 조회에 수정된 code 반영 ✓ / caption 만 PATCH → 나머지 유지 ✓ / 타인 수정→403 ✓ / 없는 marker→404 ✓ / code 빈 문자열→400 ✓.

> `core-api/docs/revisitable-decisions.md` 항목 ②(미구현이던 스니펫 내용 수정)를 구현 완료로 갱신.

## 2026-05-27 20:54:41 KST · core-api · docs: 재검토 결정 문서에 목록 조회 필터링/페이징 방식 추가

**요청 한 줄**: 앞서 다룬 조회 필터링 구현 방식도 재검토 결정 문서에 추가.

**한 줄 요약**: `core-api/docs/revisitable-decisions.md` 에 항목 ③ "목록 조회: 필터 전달·동적 쿼리·페이징 방식" 추가. (a) flat `@RequestParam`, (b) Criteria API 동적 predicate(JPQL `:param IS NULL` 의 Postgres 42P18 회피), (c) cursor(keyset) 무한스크롤·total 없음 — 각각의 의도/대안(POST search·RSQL / QueryDSL·Specification / offset·count)/확장법(DTO 묶기·QueryDSL 교체·count 병행·정렬키 추가)/재검토 트리거 정리.

코드 변경 없음(문서만).

## 2026-05-27 20:49:27 KST · core-api · docs: 재검토 가능한 설계 결정 문서 신설

**요청 한 줄**: 추후 바뀔 수 있는 설계 결정(현재 구현·의도·대안·확장법)을 한 문서에 모아 기록.

**한 줄 요약**: `core-api/docs/revisitable-decisions.md` 추가. "왜 그렇게 골랐고 / 어떤 대안이 있고 / 언제·어떻게 바꾸면 되는가"를 항목별 틀(현재 구현·의도/근거·다른 선택지·확장/변경 방법·재검토 트리거·관련 코드)로 정리하는 살아있는 문서.

### 최초 항목 2개
- **① 케이스 수정 시 스니펫/첨부 "제거"의 정리 방식** — 현재는 unlink → orphan → GC(24h). "숨김 즉시, 청소 나중". 의도(트랜잭션/크래시 안전: PATCH 안에선 컬럼 플립만, 파일 I/O 는 GC 단일 작업으로), 대안(인라인 즉시삭제 / afterCommit 즉시정리+GC 백스톱), 확장법((B) afterCommit 으로 올리기), 재검토 트리거(스토리지 비용·cascade 비대칭·undo 정식화·공유모델 전환).
- **② 스니펫/첨부 내용 수정 엔드포인트(미구현)** — 개념 3층(소속/본문태그/내용) 분리. 어댑터 upsert 는 이미 있고 `PATCH /error-snippets/{markerId}` (markerId 고정) 만 얹으면 됨.

코드 변경 없음(문서만). 관련: `core-api/docs/attachment-lifecycle.md`.

## 2026-05-27 20:12:54 KST · core-api · fix: 에러케이스 생성 입력 검증 보강(잘못된 입력 400, 연결 가로채기 차단)

**요청 한 줄**: 생성 기능의 미흡한 점 A·B·D를 적절한 동작으로 수정.

**한 줄 요약**: 잘못된 입력이 500 나던 것을 **400**으로 바로잡고, 이미 다른 케이스에 연결된 스니펫/첨부를 새 케이스가 **가로채는 것을 차단**. 생성/수정 경로의 검증을 대칭으로 맞춤.

### 수정 내용
- **A) 미존재/타인 소유 markerId → 500→400**: `CreateErrorCaseUseCase.resolve{Snippets,Attachments}` 의 `require(...)`(IllegalArgumentException → 핸들러 없어 500)를 수정 경로와 동일한 `ErrorCaseLinkException` 으로 통일. `ErrorCaseController.create` 에 `ErrorCaseLinkException → 400` catch 추가.
- **B) 잘못된 severity code(범위 밖) → 500→400**: `Severity.fromCode` 가 던지던 IllegalArgumentException(500)을 경계에서 차단. `CreateErrorCaseRequest`/`UpdateErrorCaseRequest` 의 `severity` 에 `@Min(1) @Max(4)` 추가 → `@Valid` 가 400 처리. (Severity enum S1..S4 = 1..4 범위.)
- **D) 연결 가로채기 차단(데이터 정합성)**: 생성 시 marker 의 존재·소유자만 보고 **이미 다른 케이스에 연결됐는지는 안 봐서**, 케이스1에 붙은 스니펫을 케이스2 생성에 넣으면 `errorCaseId` 가 덮어써져 케이스1에서 떨어져 나가던 버그. `resolve*` 에 `errorCaseId == null` 검증 추가 → 위반 시 400. (수정 경로엔 이미 있던 검증을 생성 경로에도 대칭 적용.)

### 변경 파일(수정)
- `application/usecase/CreateErrorCaseUseCase` — resolve* 를 `ErrorCaseLinkException` 사용 + already-linked 검증 추가.
- `presentation/web/ErrorCaseController` — create 에 `ErrorCaseLinkException → 400`.
- `presentation/.../CreateErrorCaseRequest`, `UpdateErrorCaseRequest` — `severity @Min(1) @Max(4)`.

### 검증 (core-api 직접, internal JWT)
미존재 marker→400 ✓ / severity=99→400(생성·PATCH 양쪽) ✓ / severity=2→201 ✓ / 이미 연결된 스니펫 가로채기→400 ✓ / 정상 생성→201 ✓.

> 남은 항목 **C**(scope 가 검증 없는 자유 문자열, scope↔workspaceId 정합성 없음)는 설계 결정이 필요해 이번 범위에서 제외.

## 2026-05-27 19:51:34 KST · core-api · feat: 에러케이스 수정 시 스니펫·첨부 선언형 재연결

**요청 한 줄**: 케이스 수정(PATCH)에서 스니펫·첨부 재연결을 선언형(A 방식)으로 구현.

**한 줄 요약**: `PATCH /api/v1/error-cases/{id}` 본문에 `snippetMarkerIds`/`attachmentMarkerIds`(둘 다 nullable List) 추가. 클라가 **최종 marker 집합**을 보내면 서버가 현재 연결과 diff 해 추가/해제한다. `null`=유지, `[]`=전부 해제, `[..]`=그 집합으로 맞춤.

### 설계 결정
- **선언형(desired set) > 명령형(add/remove 액션)**: 클라가 "원하는 최종 상태"만 보내고 서버가 diff 계산. PATCH 의 멱등성·단순성 확보.
- **diff = 집합 차집합**: `desired = 요청.distinct().toSet()`, `current = findAllByErrorCaseId().markerId.toSet()`, `toAdd = desired − current`, `toRemove = current − desired`, 교집합은 no-op. markerId 문자열만으로 비교, `toAdd` 만 객체 조회해 검증.
- **toAdd 검증**: 존재 / 소유자 == 요청자 / 아직 미연결(errorCaseId == null). 하나라도 위반 시 `ErrorCaseLinkException` → **400**.
- **toRemove = 연결 해제만**(errorCaseId=null → orphan). 즉시 삭제 안 함 — 기존 orphan GC 가 TTL 후 정리(파일+row). 케이스 삭제 cascade 와 구분.
- **null/[] 시맨틱**: 필드 미포함(null)이면 reconcile 자체를 건너뜀(연결 유지). 본문만 고치는 PATCH 는 스니펫·첨부를 안 건드림.

### 변경 파일
- **수정** `application/port/CodeSnippetRepositoryPort`, `ErrorCaseAttachmentRepositoryPort` — `linkToCase(markerIds, caseId)` / `unlinkFromCase(markerIds)` 추가.
- **수정** `infrastructure/jpa/CodeSnippetJpaRepository`, `AttachmentJpaRepository` — 벌크 `@Modifying(clearAutomatically=true)` UPDATE 2개씩.
- **수정** 두 어댑터 — link/unlink 구현(빈 리스트 no-op 가드).
- **수정** `presentation/.../UpdateErrorCaseRequest`, `application/command/UpdateErrorCaseCommand` — `snippetMarkerIds`/`attachmentMarkerIds: List<String>? = null` 추가.
- **수정** `application/usecase/UpdateErrorCaseUseCase` — `reconcileSnippets`/`reconcileAttachments`(diff) 추가, 같은 트랜잭션, 재연결 후 애그리거트 재로드해 응답. `ErrorCaseLinkException` 신설.
- **수정** `presentation/web/ErrorCaseController` — PATCH 에 2필드 전달 + `ErrorCaseLinkException` → 400 매핑.

### 검증 (core-api 직접, internal JWT)
스니펫 3개·케이스 생성 후: `[S1,S2]` → PATCH `[S2,S3]`(S1 해제·S2 유지·S3 추가) ✓ / title 만 PATCH → 유지 ✓ / `[]` → 전부 해제 ✓ / 타인 소유 marker → 400 ✓ / 미존재 marker → 400 ✓.

## 2026-05-26 21:03:42 KST · core-api · feat: 에러케이스 목록 조회 (cursor 무한스크롤 + 필터)

**요청 한 줄**: 목록 조회 구현 — 필터는 워크스페이스·상태 + 추가(심각도·지문), 페이징은 무한스크롤(cursor).

**한 줄 요약**: `GET /api/v1/error-cases` 추가. **keyset(cursor) 기반**(createdAt DESC, id DESC) 무한스크롤, 응답 `{ items, nextCursor, hasNext }`. 필터: `workspaceId`/`status`/`severity`/`fingerprint`. 스코프: workspaceId 지정 시 멤버(READ+) 전체, 미지정 시 본인 소유 케이스.

### 설계 결정
- **cursor(keyset) > offset**: 에러케이스는 append-heavy 피드라 offset 드리프트(중복/누락)·깊은 페이지 성능 저하가 큼. keyset 으로 안정·일정 성능. (논의 후 선택)
- **Criteria API 로 동적 쿼리**: JPQL `:param IS NULL` + null 바인드가 Postgres 에서 `could not determine data type`(42P18) 유발 → 필터가 있을 때만 predicate 추가하는 Criteria 로 회피. keyset 조건도 Criteria 로.
- **요약 DTO**(상세보다 가벼움): 스니펫/첨부 본문 제외(N+1 회피), id/title/status/severity/workspaceId/fingerprint/exceptionClass/createdAt/occurredAt.
- **opaque cursor**: base64url("createdAt|id"), 잘못된 커서는 무시(처음부터). size 1~100.
- **null meta 가드(버그 수정)**: 모든 meta 컬럼이 null 인 개인 케이스는 Hibernate 가 embedded 를 null 로 반환 → `toSummary`/`toDomain` NPE. nullable 로컬로 가드(상세 GET 의 잠재 NPE 도 함께 해결).

### 변경 파일 (core-api)
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `application/port/ErrorCaseSearch.kt` | `ErrorCaseSearchCriteria`, `ErrorCaseSummary` |
| 수정 | `application/port/ErrorCaseRepositoryPort.kt` | `search(criteria)` |
| 수정 | `infrastructure/jpa/ErrorCaseJpaRepository.kt` | (search 는 Criteria 로 → JPQL 미사용) |
| 수정 | `infrastructure/jpa/adapter/ErrorCaseRepositoryAdapter.kt` | Criteria 동적+keyset search, toSummary, meta null 가드 |
| 추가 | `application/usecase/ListErrorCasesUseCase.kt` | 스코프/권한 + 커서 인코딩 + size+1 hasNext |
| 추가 | `presentation/web/dto/response/ErrorCaseListResponse.kt` | 요약/목록 응답 |
| 수정 | `presentation/web/ErrorCaseController.kt` | `GET /api/v1/error-cases` (필터/커서) |

### 검증 (로컬, ws5: 6=WRITE/7=READ/8=비멤버)
- 워크스페이스 멤버(6) 전체 목록, READ멤버(7)=200, 비멤버(8)=403.
- severity=1 필터 정확, status=OPEN 필터, 잘못된 status=400.
- 내 케이스(ws 미지정)=개인+소유 ws 케이스, 개인 케이스 상세 GET=200(null meta 수정).
- 커서 size=2: p1/p2 중복·누락 없이 최신순 연속.

### 후속
- 키워드 검색(title/exceptionClass), 기간(createdAt/occurredAt), 작성자 필터.
- status 컬럼 인덱스(데이터 증가 시).


**요청 한 줄**: 에러케이스 조회/수정/삭제 구현(삭제는 기존 완료) → GET 상세 + PATCH 수정 추가.

**한 줄 요약**: `GET /api/v1/error-cases/{id}`(전체 애그리거트 상세) + `PATCH /api/v1/error-cases/{id}`(부분 수정) 추가. 권한은 역할 반영 — **조회: 개인=소유자 / 워크스페이스=멤버(READ+)**, **수정·삭제: 소유자 전용**. 리포지토리에 full-load(`findById`)·`update` 추가, 도메인에 `ErrorCase.update()`.

### 설계 결정
- **권한 매트릭스**: 조회는 워크스페이스 멤버(READ+)면 가능(`getViewerRole != null`), 개인 케이스는 소유자만. 수정/삭제는 소유자 전용(워크스페이스 ADMIN 모더레이션은 후속).
- **findById = 전체 애그리거트**: ErrorCaseEntity + 스니펫(`findAllByErrorCaseId`) + 첨부 로드 → `reconstitute`. 어댑터에 자식 toDomain 매퍼 재추가.
- **update = 코어 필드만**: 도메인 `update(title, scope, snapshot, description, meta)` + `repository.update`(엔티티 merge, 자식 연결 미변경). 스니펫·첨부 재연결, 워크스페이스 이동, occurredAt 변경은 범위 밖(후속).
- **PATCH 시맨틱**: 보낸 필드만 변경(null=유지). `paste` 가 오면 스냅샷/지문 재추출.
- **UpdateErrorCaseRequest 정리**: 인라인 snippets/attachments·workspaceId·environmentTags 제거 → title/scope/paste/description/severity/environment(모두 nullable).

### 변경 파일 (core-api)
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `domain/model/ErrorCase.kt` | `update(...)` 메서드 |
| 수정 | `application/port/ErrorCaseRepositoryPort.kt` | `findById`, `update` |
| 수정 | `infrastructure/jpa/adapter/ErrorCaseRepositoryAdapter.kt` | findById(full load)·update + 스니펫/첨부 toDomain |
| 추가 | `application/usecase/GetErrorCaseUseCase.kt` | 조회+읽기권한, `ErrorCaseAccessDeniedException` |
| 추가 | `application/usecase/UpdateErrorCaseUseCase.kt` | 소유자 전용 부분수정(+paste 시 스냅샷 재추출) |
| 추가 | `application/command/UpdateErrorCaseCommand.kt` | 수정 커맨드 |
| 추가 | `presentation/web/dto/response/ErrorCaseDetailResponse.kt` | 상세 응답 + `from(domain)` |
| 수정 | `presentation/web/dto/request/UpdateErrorCaseRequest.kt` | markerId 모델에 맞게 정리(부분수정 필드만) |
| 수정 | `presentation/web/ErrorCaseController.kt` | `GET /{id}`, `PATCH /{id}` + 404/403 매핑 |

### 검증 (로컬, workspace 5: 6=WRITE owner / 7=READ / 8=비멤버)
- GET: 소유자(6)=200 / READ멤버(7)=200 / 비멤버(8)=403 / 없는 id=404.
- PATCH: READ멤버(7)=403(소유자 전용) / 소유자(6)=200(title·severity 반영).
- DELETE: READ멤버(7)=403 / 소유자(6)=204 / 삭제후 GET=404.

### 후속
- **목록 조회**(GET /error-cases?workspaceId=/my) — 페이징/필터.
- 수정 시 **스니펫·첨부 재연결**, 워크스페이스 이동, occurredAt 변경.
- 워크스페이스 **ADMIN 모더레이션**(타인 케이스 수정/삭제) 정책.


**요청 한 줄**: 워크스페이스 역할(READ/WRITE/ADMIN)을 콘텐츠 권한에도 반영 — 지금까진 멤버 여부만 봤음.

**한 줄 요약**: core-api 가 iam-api 에 "멤버냐"(`existsWorkspaceForUser`)만 묻던 것을 **"역할이 뭐냐"(`getViewerRole`)** 로 확장. 워크스페이스에 에러케이스를 만들려면 **WRITE 이상** 필요(READ 멤버·비멤버는 403). iam-api 변경 없음 — `GET /workspaces/{id}` 가 이미 `viewerRole` 을 반환하므로 core-api 가 그 값을 읽도록만 변경.

### 설계 결정
- **역할 조회로 일원화**: `WorkspaceQueryPort.existsWorkspaceForUser(...)→Boolean` 제거, `getViewerRole(...)→WorkspaceRole?`(비멤버 null) 추가. 멤버 여부 = role != null, 작성 가능 = `role.canWriteContent()`(WRITE/ADMIN).
- **core-api 자체 WorkspaceRole(BC 경계)**: iam-api 도메인을 끌어오지 않고 core-api 측 표현(READ/WRITE/ADMIN + `canWriteContent()`)을 둠. iam 응답의 `viewerRole` 문자열을 `fromCode` 로 매핑.
- **개인 케이스(workspaceId=null)는 게이트 없음**: 워크스페이스 미지정 케이스는 소유자 개인 콘텐츠 → 역할 검사 안 함.
- **403 매핑**: 비멤버/역할부족 → `WorkspaceAccessDeniedException` → 컨트롤러에서 403. (이전 `require`(IllegalArgumentException)→500 이던 "not accessible" 도 함께 403 으로 정리.) iam-api 5xx/timeout 은 기존대로 CircuitBreaker fallback(IamApiUnavailableException).

### 변경 파일 (core-api 만)
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `errorcase/application/port/WorkspaceQueryPort.kt` | `getViewerRole` + `WorkspaceRole` enum(canWriteContent) — `existsWorkspaceForUser` 제거 |
| 수정 | `errorcase/infrastructure/iam/IamWorkspaceQueryAdapter.kt` | GET /workspaces/{id} 응답의 `viewerRole` 파싱(403/404→null, 5xx→fallback), `IamWorkspaceView` DTO |
| 수정 | `errorcase/application/usecase/CreateErrorCaseUseCase.kt` | 워크스페이스 지정 시 역할 조회→WRITE 이상 요구, `WorkspaceAccessDeniedException` |
| 수정 | `errorcase/presentation/web/ErrorCaseController.kt` | `WorkspaceAccessDeniedException` → 403 매핑 |

### 검증 (로컬, core-api↔iam-api)
- workspace(5=ADMIN,6=WRITE,7=READ) 에 에러케이스 생성: **WRITE(6)=201 / READ(7)=403 / 비멤버(8)=403**.
- workspaceId 없는 개인 케이스: READ 유저(7)도 **201**(게이트 없음).

### 후속 (콘텐츠 역할 추가 적용 여지)
- 케이스 **삭제**: 현재 소유자만 — 워크스페이스 ADMIN 도 허용할지 정책 검토.
- 케이스 **조회/수정**(아직 미구현 GET/PATCH): READ=조회 / WRITE=수정 등 역할 반영 시 동일 `getViewerRole` 재사용.


**요청 한 줄**: 초대 링크(LINK)는 보통 공유용 다회용이 관례 → LINK 만 다회용으로(만료/취소 전까지 여러 명 합류), EMAIL(특정인)은 1회용 유지.

**한 줄 요약**: `AcceptInvitationUseCase` 를 초대 타입별로 분기 — **EMAIL**: 기존대로 원자 claim(PENDING→ACCEPTED, 1회용). **LINK**: 토큰을 소비하지 않고 `isPending`(PENDING+미만료)이면 합류 허용(다회용). 통제는 만료(expiresAt)+취소(revoke)+멤버 `uq_iam_workspace_member_ws_user` 유니크(중복 합류 방지).

### 설계 결정
- **타입별 수락 정책**: EMAIL=특정 1명 → 1회용(claim 소비). LINK=공유 → 다회용(소비 안 함, PENDING 유지). 관례(Slack/Discord/Notion 초대 링크)와 일치.
- **다회용 통제**: 별도 status 추가 없이 LINK 는 PENDING 으로 유지(=활성 링크). 만료는 `isPending` 의 `now<expiresAt` 로 lazy 판정, 취소는 revoke→REVOKED 로 즉시 무효. 동일 유저 중복 합류는 멤버 유니크 제약으로 차단.
- **max-uses 미구현**: 만료+취소로만 통제(필요 시 후속). LINK 도 ADMIN 역할 부여 금지 유지.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `workspace/application/usecase/AcceptInvitationUseCase.kt` | `when(type)` 분기: EMAIL=claim(1회용) / LINK=isPending 확인 후 소비 없이 합류(다회용) |

### 검증 (로컬)
- LINK 생성 → `inviteToken`/`inviteUrl` 반환(메일 X). 서로 다른 유저 6·7 이 **같은 토큰으로 둘 다 합류(200)** → 멤버 3명. 같은 유저 재합류 200(멱등, 유니크 제약으로 중복 row 없음).
- revoke(204) 후 동일 토큰 accept → **410**(무효 링크 차단).
- EMAIL 은 그대로 1회용(재사용 410) — 이전 검증 유지.

### 후속
- (선택) LINK max-uses(최대 사용 횟수) 상한.
- 만료된 LINK 가 DB 상 PENDING 으로 남아 `listPending` 에 노출될 수 있음(usable 은 false) — 표시/정리 정책 검토.

## 2026-05-26 16:57:30 KST · iam-api · feat: 초대 수락을 토큰=권한 모델로 완화 + 1회용 원자 claim

**요청 한 줄**: 미가입자가 초대받아 가입(GitHub OAuth)하면 계정 이메일이 초대 이메일과 다를 수 있는데, 현재 "계정 이메일 == 초대 이메일" 강제 일치가 신규 가입자의 수락을 막음 → 토큰=권한 모델로 완화.

**한 줄 요약**: `AcceptInvitationUseCase` 의 EMAIL 강제 이메일 일치 검사를 **제거**(토큰 소지 자체가 메일함 통제 증거). 대신 1회용 보장을 위해 **원자적 claim**(`PENDING`+미만료일 때만 `ACCEPTED` 로 전이하는 조건부 UPDATE) 추가 — 동시/재사용 시 0행 → 거부.

### 설계 결정
- **토큰=권한(token-as-capability)**: 유효·미만료·미사용 토큰 + 로그인된 ACTIVE 유저면 수락. EMAIL/LINK 모두 동일(LINK 는 원래 이메일 검사 없었음). GitHub OAuth 가입 이메일 불일치/null 문제 해소.
- **1회용 원자 claim**: 이메일 일치 게이트가 사라지므로 동시성 보호가 중요해짐 → `claimForAcceptance` 조건부 UPDATE 로 정확히 1명만 점유. (도메인이 entity↔domain remap 구조라 `@Version` 대신 조건부 UPDATE 선택.) 멤버 추가는 claim 성공 후 같은 트랜잭션에서 수행 → 실패 시 전체 롤백(재시도 가능).
- **유지**: 로그인 필수(`existsActive`), TTL, 토큰 해시 저장, 역할(ADMIN 금지), 멤버 중복 skip. **포기**: 초대 전달(forward) 시 제3자 수락 가능 → TTL·1회용·관리자 멤버 제거로 완화(Slack/Notion/GitHub 초대 링크와 동일 모델).

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `workspace/application/usecase/AcceptInvitationUseCase.kt` | 이메일 강제 일치 제거, claim-first 흐름, 감사 로그 |
| 수정 | `workspace/application/port/WorkspaceInvitationRepositoryPort.kt` | `claimForAcceptance(tokenHash, userId, now): Boolean` |
| 수정 | `workspace/infrastructure/jpa/WorkspaceInvitationJpaRepository.kt` | `@Modifying` 조건부 UPDATE(PENDING+미만료→ACCEPTED) |
| 수정 | `workspace/infrastructure/jpa/WorkspaceInvitationRepositoryAdapter.kt` | claim 구현(rows==1) |

### 검증 (로컬, MailHog)
- 초대 이메일(newcomer@external.com) ≠ 수락자 계정 이메일(invitee@example.com) → **수락 200** (이전엔 거부). 멤버 목록에 WRITE 로 추가 확인.
- 같은 토큰 재사용 → **410 Gone**("not usable (already used...)") — 1회용 claim 차단.

### 후속
- 동시성(서로 다른 유저가 같은 토큰 동시 수락)은 조건부 UPDATE 의 행 락으로 직렬화 보장(순차 재사용으로 검증, 진짜 동시 테스트는 미수행).
- (선택) "알려진 가입 유저에게 바인딩된 초대 보호"(2번 절충)는 미적용 — 필요 시 추가.

## 2026-05-26 16:31:12 KST · iam-api · feat: OAuth 로그인 후 복귀(returnUrl→redirectTo) — 로그인 선행 후 원래 작업 이어가기

**요청 한 줄**: 미가입/미로그인 사용자가 로그인 필요한 곳(예: 초대 수락 `/my-page`)에 접근하면 로그인(GitHub OAuth)으로 보내고, 로그인/가입이 끝나면 원래 가려던 경로로 돌아와 작업을 이어가게.

**한 줄 요약**: SPA-driven OAuth(현행 B 방식) 유지하면서, **복귀 경로(returnUrl)를 OAuth 왕복에 실어 콜백 응답으로 돌려주는 통로**를 추가. `authorize` 요청에 `returnUrl`(앱 내부 경로)을 받아 **httpOnly 쿠키(`iam_oauth_return`, state 쿠키와 동일 수명/경로)** 로 보관, `callback` 에서 꺼내 `TokenResponse.redirectTo` 로 반환 + 쿠키 만료. 세션 기반 SavedRequest 의 stateless 등가물.

### 설계 결정
- **B 방식 유지 + returnUrl 통로**: 토큰을 JSON 으로 주는 현행 SPA-driven 흐름을 그대로 두고(서버 302/세션 미도입), 복귀 경로만 쿠키로 stash→콜백에서 echo. SPA 가 `redirectTo` 로 이동.
- **open-redirect 방지**: `sanitizeReturnUrl` 로 **앱 내부 상대경로만** 허용(`/` 시작, `//`/`\`/`://`/공백·제어문자 거부, 512자 제한). 위반 시 무시(쿠키 미발급) → SPA 기본 홈 폴백. authorize·callback 양쪽 검증(방어).
- **stash 위치 = 쿠키**: 기존 state 가 이미 쿠키(stateless)라 일관되게 returnUrl 도 쿠키. 서버 세션/스토어 불필요.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `auth/presentation/web/dto/request/StartOAuthRequest.kt` | `returnUrl: String?` |
| 수정 | `auth/presentation/web/dto/response/TokenResponse.kt` | `redirectTo: String?` |
| 수정 | `auth/config/AuthCookieProperties.kt` | `oauthReturnName`(기본 `iam_oauth_return`) |
| 수정 | `auth/infrastructure/security/AuthCookieFactory.kt` | `oauthReturnCookie` / `expiredOAuthReturnCookie` |
| 수정 | `auth/presentation/web/OAuthController.kt` | authorize: returnUrl 검증 후 쿠키 set / callback: 쿠키 읽어 redirectTo 반환+만료 / `sanitizeReturnUrl`·`readReturnCookie` |

### 검증 (로컬)
- `authorize {returnUrl:"/my-page"}` → `Set-Cookie: iam_oauth_return=/my-page`(+state) 확인.
- `returnUrl: "https://evil.com"`, `"//evil.com"` → 쿠키 미발급(거부) 확인.
- callback 의 redirectTo 반환은 실제 GitHub code 교환이 필요해 풀 e2e 미검증(쿠키 read→sanitize→echo 로직만, authorize 쪽 쿠키 왕복으로 메커니즘 검증).

### 후속 (이번 범위 밖)
- **미가입자 초대 수락 시 email-match 완화**: GitHub 가입 이메일이 초대 이메일과 다를 수 있어 `AcceptInvitationUseCase` 의 강제 일치가 신규 가입자를 막을 수 있음 → 토큰=권한 모델로 완화 검토(직전 논의).
- SPA: 미로그인 감지 → `returnUrl=현재경로` 로 로그인 시작 → `redirectTo` 로 복귀(프론트 구현).

## 2026-05-24 16:43:48 KST · iam-api · chore: 워크스페이스 초대 메일/수락 end-to-end 검증 + 로컬 실제 SMTP 발송 지원

**요청 한 줄**: 워크스페이스 초대(EMAIL/LINK)에서 "메일 실제 발송 → 수락 → 멤버 추가"가 실제로 동작하도록(실제 메일 전송 포함) 구현.

**한 줄 요약**: 확인 결과 해당 로직은 **이미 구현·배선돼 있었음**(`InviteMemberUseCase`→`SmtpInvitationEmailSenderAdapter` 발송, `AcceptInvitationUseCase`→`WorkspaceMember.join` 멤버 추가, `MemberSummaryReaderAdapter` 실제 브릿지). 미검증 상태였을 뿐. local 프로파일 메일 설정을 **ENV 오버라이드 가능**하게 바꿔(기본값 MailHog 유지), 코드/프로파일 변경 없이 로컬에서도 실제 외부 SMTP(Gmail/SES/SendGrid)로 보낼 수 있게 함. dev/stg/prod 는 이미 `spring.mail.*` ENV + auth/starttls=true 로 완비돼 있었음.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `iam-api/src/main/resources/application-local.yml` | `spring.mail.{host,port,username,password}` + `mail.smtp.auth`/`starttls` 를 `IAM_MAIL_*` ENV 로(기본 MailHog). `iam.email.{provider,from-address,from-name}` 도 ENV 오버라이드 |

### 검증 (로컬, MailHog)
- 테스트 유저 2명 시드(inviter=5, invitee=6) → 워크스페이스 생성(5=ADMIN) → **EMAIL 초대 → MailHog 수신 확인** → preview usable=true → **수락(6) → 멤버 목록에 6(WRITE) 추가** 전 과정 통과.
- 실 SMTP 전환은 동일 코드 경로 + `IAM_MAIL_*` ENV 만 다름(Gmail 앱 비밀번호 / SES SMTP 자격증명 / SendGrid API 키).

### 후속
- 실제 외부 받은편지함 배달 검증은 사용자 SMTP 자격증명(ENV) 필요.
- (선택) `InvitationEmailSenderPort` 에 `expiresAt` 추가해 메일 본문의 만료시각 실제값 표기(현재 "관리자 안내 참고" 고정).

## 2026-05-22 18:52:47 KST · core-api · feat: 에러 케이스 삭제 + 첨부/스니펫 cascade (단일 삭제 루틴 재사용)

**요청 한 줄**: 케이스 삭제 시 연결된 첨부/스니펫도 함께 삭제(cascade). 기존 단일 삭제 루틴(Deleter) 재사용.

**한 줄 요약**: `DELETE /api/v1/error-cases/{id}` 추가. 소유자만 가능하며, 연결된 첨부/스니펫을 **자식 먼저 → 케이스** 순서로 `AttachmentDeleter`/`SnippetDeleter`(독립 트랜잭션·멱등) 로 지운 뒤 케이스 row 를 삭제한다.

### 설계 결정
- **단일 삭제 루틴 재사용**: cascade 가 별도 삭제 로직을 갖지 않고 GC/명시삭제와 동일한 Deleter 를 호출 → 삭제 경로 일원화.
- **클래스 레벨 @Transactional 안 둠**: 첨부는 파일(비트랜잭셔널)+row 라, 큰 트랜잭션으로 묶으면 롤백 시 "파일은 지워졌는데 row 는 errorCaseId 가 채워진 채" 남아 orphan GC(errorCaseId IS NULL)에도 안 잡히는 깨진 상태가 됨. 각 Deleter 가 독립 커밋·멱등이라 중간 실패해도 재시도로 자기치유.
- **자식 먼저 → 케이스 순서 + fail-fast**: 자식이 남았는데 케이스를 지우면 그 자식이 leaked orphan(errorCaseId=삭제된 케이스 → GC 미포착)이 되므로, 자식 삭제 실패 시 예외 전파 → 케이스 보존 → 재시도.
- **소유자 검증**: `findOwnerUserIdById` 로 owner != requester → 403, 없는 케이스 → 404.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `application/usecase/DeleteErrorCaseUseCase.kt` | cascade 삭제 + ErrorCaseNotFound/DeleteForbidden 예외 |
| 수정 | `presentation/web/ErrorCaseController.kt` | `DELETE /{id}` → 204 |
| 수정 | `application/port/ErrorCaseRepositoryPort.kt` + adapter | `deleteById` |
| 수정 | `application/port/ErrorCaseAttachmentRepositoryPort.kt` + JPA + adapter | `findAllByErrorCaseId` |
| 수정 | `application/port/CodeSnippetRepositoryPort.kt` + adapter | `findAllByErrorCaseId` (JPA 기존 메서드 활용) |

### 검증 (로컬, core-api 직접 internal JWT)
- 스니펫+첨부 생성 → 케이스 연결(id=6) → 삭제 전 snippet DELETE 403/attachment GET 200 → **case DELETE 204** → attachment GET 404·snippet DELETE 404(자식 cascade 삭제) → 재삭제 404(멱등).
- 소유권: 비소유자(999) DELETE 403, 소유자(123) DELETE 204.

### 후속 (이번 범위 밖)
- 본문 `@snippet(id)` 마커 삽입 버튼/렌더링.
- row 없는 파일 sweep, GC 메트릭/알람.

---

## 2026-05-22 18:43:12 KST · core-api · feat: 코드 스니펫을 첨부와 동일한 독립 생성/연결 모델로 전환 [Breaking]

**요청 한 줄**: 에러 케이스 작성 시 "Code Snippet 생성" 버튼 → 모달에서 코드 입력 → 저장하면 첨부처럼 markerId 를 받는다. 본문에 안 넣은 스니펫도 "관련 코드"로 노출(별도 UX 목업 `component/Concept-Snippet-Display.html`). 본문 마커 삽입 버튼은 보류.

**한 줄 요약**: 인라인으로 케이스 요청에 실려 케이스와 함께 저장되던 스니펫을, **첨부(Attachment)와 완전 대칭**으로 전환 — `POST /api/v1/error-snippets` 로 먼저 독립 생성(markerId/embedToken 반환, errorCaseId=null), 케이스 생성은 `snippetMarkerIds` 로 연결(`attachmentMarkerIds` 와 동일). 미연결 스니펫은 첨부와 동일한 TTL GC + 명시적 DELETE 로 정리.

### 설계 결정
- **첨부 패턴 완전 미러**: 독립 생성 → markerId → 케이스 연결(`ErrorCaseRepositoryAdapter` 가 marker 로 찾아 errorCaseId 세팅, 첨부 블록과 동일). 멘탈모델 통일.
- **요청 계약 변경(Breaking)**: `CreateErrorCaseRequest.snippets`(인라인) 제거 → `snippetMarkerIds`. 프론트가 모달 "저장"에서 즉시 `POST /error-snippets` 호출하는 새 흐름으로 가므로 인라인은 레거시 → 제거(둘 다 유지하는 B안 대신 A안 선택).
- **소유권/orphan 필드 추가**: `CodeSnippet`/`CodeSnippetEntity` 에 `uploadedByUserId`/`uploadedAt`/`errorCaseId` + `embedToken()`(`@snippet(markerId)`). 케이스 연결 시 업로더 검증(`resolveSnippets`), GC TTL 판정에 사용.
- **단일 삭제 루틴 + GC**: `SnippetDeleter`(row만 — 스니펫은 파일 없음, 멱등), `PurgeOrphanSnippetsUseCase`(배치 루프), `OrphanSnippetGcScheduler`(@Scheduled+@SchedulerLock, 잡명 `purgeOrphanSnippets`). 첨부 GC 와 대칭.
- **미삽입 스니펫 UX**: 첨부와 동일 — 섹션에 모두 나열, 본문 삽입은 선택, 미삽입은 "관련 코드"로 노출(목업으로 합의).

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `domain/model/vo/CodeSnippet.kt` | uploadedByUserId/uploadedAt/errorCaseId + embedToken() |
| 수정 | `infrastructure/jpa/entity/CodeSnippetEntity.kt` | uploaded_by_user_id/uploaded_at 컬럼 + `ix_snippet_orphan_gc` |
| 추가 | `application/port/CodeSnippetRepositoryPort.kt` | 독립 저장 포트(첨부 대칭) |
| 수정 | `infrastructure/jpa/CodeSnippetJpaRepository.kt` | findByMarkerId/findAllByMarkerIdIn/exists/deleteByMarkerId/findUnlinked... |
| 추가 | `infrastructure/jpa/adapter/CodeSnippetRepositoryAdapter.kt` | 포트 구현 |
| 추가 | `application/command/CreateSnippetCommand.kt`, `application/usecase/CreateSnippetUseCase.kt` | 독립 생성 |
| 추가 | `application/usecase/SnippetDeleter.kt`, `DeleteSnippetUseCase.kt` | 단일 삭제 + 명시 삭제(소유자·미연결 검증) |
| 추가 | `presentation/web/SnippetController.kt` | `POST/DELETE /api/v1/error-snippets` |
| 추가 | `application/usecase/PurgeOrphanSnippetsUseCase.kt`, `config/SnippetProperties.kt`, `infrastructure/scheduling/OrphanSnippetGcScheduler.kt` | orphan GC |
| 수정 | `presentation/web/dto/request/CreateErrorCaseRequest.kt` | `snippets` 제거 → `snippetMarkerIds` |
| 수정 | `application/command/CreateErrorCaseCommand.kt` | SnippetInput 제거 → snippetMarkerIds |
| 수정 | `application/usecase/CreateErrorCaseUseCase.kt` | inline 생성 제거 → `resolveSnippets`(검증), MarkerIdGenerator 의존 제거 |
| 수정 | `presentation/web/ErrorCaseController.kt` | snippetMarkerIds 매핑 |
| 수정 | `infrastructure/jpa/adapter/ErrorCaseRepositoryAdapter.kt` | 스니펫 insert → marker 로 연결(첨부와 동일), 미사용 매퍼 제거 |

### 검증 (로컬, core-api 직접 internal JWT)
- 스니펫 생성 → `markerId`/`@snippet(...)` 반환. 미연결 DELETE 204. 케이스 생성 `snippetMarkerIds` 연결 201. 연결된 스니펫 DELETE 403.
- 스니펫 GC: TTL=0/5초 cron 으로 `[snippet-gc] purged 1 orphan snippets` 확인.

### 후속 (이번 범위 밖)
- 본문 `@snippet(id)` 마커 삽입 버튼(프론트) + 렌더링.
- 케이스 삭제 시 스니펫/첨부 cascade — Deleter 재사용.
- 참고 UX 목업: `component/Concept-Snippet-Display.html`.

---

## 2026-05-21 22:03:32 KST · core-api · feat: orphan 첨부 정리 — TTL GC + 명시적 삭제 (단일 삭제 루틴)

**요청 한 줄**: 첨부를 먼저 업로드했지만 끝내 에러케이스를 등록하지 않은 경우(폼 이탈/크래시) 누수되는 미연결 첨부(파일+DB row)를 어떻게 정리할지. 실무 표준(업로드-즉시 / 링크-온-서밋 / 백그라운드 TTL GC + UI 제거 시 즉시 삭제)으로 구현.

**한 줄 요약**: 첨부 1건 삭제를 **단일 멱등 루틴 `AttachmentDeleter`**(파일 먼저→row, `deleteIfExists`/`deleteByMarkerId` 라 재시도·동시실행 안전)로 통일하고, 이를 ① **`PurgeOrphanAttachmentsUseCase`**(미연결+TTL경과 첨부를 배치로 GC)와 ② **명시적 `DELETE /api/v1/error-attachments/{markerId}`**(업로더 본인의 미연결 첨부만)가 재사용. GC 트리거는 **ShedLock 분산 락**으로 다중 인스턴스 중 한 곳에서만 실행.

### 설계 결정
- **단일 삭제 루틴**: GC·명시삭제·(추후)케이스 cascade 의 삭제 경로를 하나로. **파일 먼저→row** 순서라 중간 실패 시 "row 남고 파일 없음"이 되어 다음 GC 가 자연 회수(자기치유). 멱등이라 다중 인스턴스 동시 삭제도 무해 → **삭제 정확성은 락 없이도 보장**.
- **GC 배치 루프**: `findUnlinkedOlderThan(threshold, batchSize)` 오래된 순 페이징, 각 건 독립 트랜잭션(`AttachmentDeleter`), 한 건 실패가 배치를 막지 않음. 진행 없음(전부 실패)이면 중단해 무한 루프 방지. TTL 기본 24h, 배치 500 (`core.attachment.orphan-ttl`/`orphan-gc-batch-size`).
- **ShedLock(다중 인스턴스 안전)**: `@Scheduled`(기본 10분, `core.attachment.orphan-gc-cron`) + `@SchedulerLock`. 락 저장소는 Postgres `shedlock` 테이블 — 별도 마이그레이션 도구가 없어 **JPA 엔티티(`ShedLockEntity`)로 ddl-auto 자동 생성**. `usingDbTime()` 으로 인스턴스 시계차 무관. ShedLock 6.10.0 이 Spring Boot 4 에서 정상 동작 확인.
- **GC 인덱스**: `(error_case_id, uploaded_at)` 복합 인덱스 추가(`error_case_id IS NULL AND uploaded_at < ?` 쿼리용). 운영선 부분 인덱스로 추가 최적화 여지.
- **명시적 삭제 정책**: 업로더 본인 + **미연결 첨부만**. 이미 케이스에 묶인 첨부는 403(케이스 삭제 경로로) — 케이스 본문의 깨진 참조 방지.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `.../application/port/AttachmentStoragePort.kt` | `delete(markerId, fileName)` (멱등) |
| 수정 | `.../infrastructure/storage/LocalFileSystemAttachmentStorageAdapter.kt` | `deleteIfExists` 구현 |
| 수정 | `.../application/port/ErrorCaseAttachmentRepositoryPort.kt` | `deleteByMarkerId`, `findUnlinkedOlderThan` |
| 수정 | `.../infrastructure/jpa/AttachmentJpaRepository.kt` | `deleteByMarkerId`, `findByErrorCaseIdIsNullAndUploadedAtBeforeOrderByUploadedAtAsc(Pageable)` |
| 수정 | `.../infrastructure/jpa/adapter/AttachmentRepositoryAdapter.kt` | 위 두 메서드 구현 |
| 수정 | `.../infrastructure/jpa/entity/AttachmentEntity.kt` | `ix_attachment_orphan_gc (error_case_id, uploaded_at)` 인덱스 |
| 추가 | `.../application/usecase/AttachmentDeleter.kt` | 단일 삭제 루틴(파일+row, 멱등, @Transactional) |
| 추가 | `.../application/usecase/PurgeOrphanAttachmentsUseCase.kt` | TTL GC 배치 루프 |
| 추가 | `.../application/usecase/DeleteAttachmentUseCase.kt` | 명시적 삭제(소유자+미연결 검증) + `AttachmentDeleteForbiddenException` |
| 수정 | `.../presentation/web/ErrorCaseAttachmentController.kt` | `DELETE /{markerId}` → 204 |
| 수정 | `.../config/AttachmentStorageProperties.kt` | `orphanTtl`(기본 24h), `orphanGcBatchSize`(기본 500) |
| 추가 | `.../shared/scheduling/SchedulingConfig.kt` | `@EnableScheduling`+`@EnableSchedulerLock`, JdbcTemplate LockProvider |
| 추가 | `.../shared/scheduling/ShedLockEntity.kt` | `shedlock` 테이블 매핑(ddl-auto 생성용) |
| 추가 | `.../infrastructure/scheduling/OrphanAttachmentGcScheduler.kt` | `@Scheduled`+`@SchedulerLock` → PurgeOrphanAttachmentsUseCase |
| 수정 | `core-api/build.gradle.kts` | shedlock-spring / shedlock-provider-jdbc-template 6.10.0 |

### 검증 (로컬, gateway+core-api+iam-api)
- 업로드 → GET 200 → **DELETE 204** → GET 404 (파일+row 제거) — 단일 삭제 루틴 e2e 통과.
- TTL=0/5초 cron 으로 띄워 orphan 업로드 → `scheduling-1` 스레드에서 `[attachment-gc] purged 1 orphan attachments (ttl=PT0S)` 로그 → 스케줄 GC + ShedLock + 배치 삭제 전 체인 동작 확인.
- ShedLock 6.10.0 + Spring Boot 4 컨텍스트 로드 + `shedlock` 테이블 자동 생성 확인.

### 후속 (이번 범위 밖)
- row 없는 파일(업로드 중 크래시) reconciliation sweep, GC 메트릭/알람.
- 케이스 삭제 시 첨부 cascade 삭제 — `AttachmentDeleter` 재사용 예정.
- `IllegalArgumentException("workspace not accessible")` 등 4xx 매핑(이전 항목과 동일 후속).

---

## 2026-05-20 21:09:31 KST · core-api · fix: CircuitBreaker 스레드로 SecurityContext 전파 (svc→svc 401 해결)

**요청 한 줄**: core-api→iam-api 호출이 계속 401. `IamWorkspaceQueryAdapter` 의 `cb.run` 블록과 `IamApiRestClientConfig` 인터셉터가 서로 다른 스레드에서 돌아 SecurityContext 가 유실되는 문제 해결.

**한 줄 요약**: core-api `ResilienceConfig` 에 TimeLimiter(5s)가 설정돼 있어 Spring Cloud CircuitBreaker 가 supplier 를 **별도 ExecutorService 스레드**에서 실행한다. 그 worker 스레드에는 요청 스레드의 `SecurityContextHolder`(thread-local)가 전파되지 않아, `IamApiRestClientConfig` 의 RestClient interceptor 가 `currentUser()` 로 사용자를 못 읽고 → internal token 미부착 → iam-api 가 401. CircuitBreaker 의 ExecutorService 를 **`DelegatingSecurityContextExecutorService`** 로 감싸 제출 스레드의 SecurityContext 를 worker 스레드로 전파하도록 수정.

### 설계 결정
- **전역 executor 래핑 (어댑터 개별 래핑 대신)**: `factory.configureExecutorService(DelegatingSecurityContextExecutorService(...))` 한 곳만 고치면 모든 `cb.run` 이 컨텍스트를 전파한다. 어댑터마다 supplier 를 수동 래핑하는 방식보다 누락 위험이 없다.
- **executor 빈으로 관리**: `@Bean(destroyMethod = "shutdown")` 로 cached thread pool 을 노출해 컨텍스트 종료 시 정리. (프레임워크 기본도 cached thread pool 을 쓰므로 동작 특성 동일.)
- **단일 인자 생성자**: `DelegatingSecurityContextExecutorService(executor)` 는 task **제출 시점**의 SecurityContext 를 캡처 → 요청마다 올바른 사용자 컨텍스트가 worker 로 넘어간다.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `core-api/.../shared/config/ResilienceConfig.kt` | `circuitBreakerExecutor` 빈 추가 + `configureExecutorService(DelegatingSecurityContextExecutorService(...))` |
| 수정 | `core-api/.../shared/config/SecurityConfig.kt` | `/error` 디스패치를 `permitAll` — 컨트롤러가 던진 4xx/5xx 의 `/error` 내부 디스패치가 보안 체인에 막혀 **401 로 둔갑하던 문제** 해결 |

### 검증 (로컬, gateway+core-api+iam-api 기동)
- `scripts/test-auth-scenarios.sh` → 8/8 PASS (이전 3·7 실패 = `/error` secured 로 인한 둔갑, 이번에 해소).
- svc→svc: `POST /api/v1/error-cases`(workspaceId 포함) 시 core-api 로그에 worker 스레드(`pool-2-thread-1`)에서 `auth=InternalAuthentication[Principal=123 ... ROLE_USER]` 확인 → 토큰 부착 → iam-api 인증 통과(존재하지 않는 workspace 라 "not accessible" 비즈니스 결과 반환).

### 후속 (이번 범위 밖)
- `IllegalArgumentException("workspace not accessible")` 가 500 으로 매핑됨 → 403/404 등 적절한 4xx 로 매핑 검토.
- `IamApiRestClientConfig`/`ErrorCaseAttachmentController` 의 임시 디버그 로그(`logger.info`/`println`) 정리.

---

## 2026-05-20 16:07:02 KST · multi · feat: 내부 서비스 간 인증 — 단명 Internal JWT 도입 (token relay 제거) [Breaking]

**요청 한 줄**: 추후 mTLS + Service Mesh 도입을 고려해, 지금은 token-relay 를 쓰지 않는 내부 서비스 간 인증 방식을 구현. 마이그레이션 시 검증 로직의 절반(서명/iss/aud)만 메쉬로 이관되고 사용자 컨텍스트 추출은 그대로 남도록 설계.

**한 줄 요약**: composite build 멤버 **`shared-internal-auth`** 신설 — 호출자가 자기 RSA 키로 서명한 단명(60s) RS256 JWT 를 `X-Internal-Auth` 헤더로 실어 보내고(`InternalTokenIssuer`), 수신 측은 `iss` 의 공개키로 서명 + `aud` + `exp` 를 검증한다. 게이트웨이는 사용자 JWT 검증 후 downstream 용 internal JWT 를 발급(평문 `X-User-Id`/`X-Roles` 주입 폐지), core-api/iam-api 는 이를 검증해 사용자 신원을 신뢰한다. core-api → iam-api 의 **사용자 Authorization relay(token relay)를 제거**하고 core-api 가 자기 키로 새 토큰(`iss=core-api`)을 발급한다.

영향 서비스: `shared-internal-auth`(신규), `gateway`, `core-api`, `iam-api`, `repo`(루트 빌드).

### 설계 결정
- **단명 Internal JWT + audience 바인딩**: `iss`(호출자)/`aud`(대상)/`sub`(원 사용자)/`roles`/`exp`(60s)/`jti`. `aud` 로 다른 서비스용 토큰 재사용을 차단(confused deputy 방지), 60s TTL 로 유출 윈도우 최소화.
- **비대칭 RS256 + 발급자별 공개키**: 메쉬의 SPIFFE JWT-SVID 와 검증 인터페이스가 동일해 마이그레이션 시 검증 코드 변경 최소. 키 배포는 현재 properties 인라인(로컬 dev 키), 운영은 ENV/secret manager 주입 전제.
- **composite build 유지**: `shared-internal-auth` 를 `includeBuild` 멤버로 추가(멀티모듈 `include` 아님). 소비 측은 GAV 좌표 `org.studieo-javry:shared-internal-auth:0.0.1-SNAPSHOT` 로 의존 — 루트 빌드 시 substitution, 단독 빌드 시 `mavenLocal()` 로 해소(각 모듈에 `mavenLocal()` 추가 + `publishToMavenLocal`).
- **iam-api 는 Jwt principal 유지(컨트롤러 무변경)**: 기존 ~10개 컨트롤러가 `@AuthenticationPrincipal Jwt` 로 `jwt.subject` 를 읽으므로, 공유 필터(Long principal) 대신 **커스텀 `BearerTokenResolver`(X-Internal-Auth 헤더) + 다중 issuer `MultiIssuerJwtDecoder`** 를 oauth2ResourceServer 에 결합. principal 이 그대로 `Jwt` 라 컨트롤러 churn 0. token relay 제거로 사용자 JWT 가 더 이상 iam-api 에 도달하지 않으므로 기존 HMAC `JwtDecoderConfig`(resource-server 검증용) 는 **삭제**.
- **core-api 는 신규로 Spring Security 추가**: 기존엔 인증 계층이 없어 평문 `X-User-Id` 를 그대로 신뢰했음. `SecurityConfig` + 공유 `InternalTokenAuthenticationFilter` 로 검증, 컨트롤러는 `@RequestHeader("X-User-Id")` → `@AuthenticationPrincipal userId: Long` 으로 전환. core-api 는 검증자이자(게이트웨이 토큰) 발급자(iam-api 호출용).
- **Filter 자동 등록 비활성화**: Spring Boot 가 `Filter` 빈을 서블릿 체인에 자동 등록하는 것을 막기 위해 `FilterRegistrationBean(...).isEnabled=false` 제공. core-api 는 `addFilterBefore` 로 보안 체인에 명시 삽입, iam-api 는 디코더 방식이라 필터 미사용.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `shared-internal-auth/` (settings/build.gradle.kts) | composite build 멤버. boot 플러그인 적용 + `bootJar` 비활성, `maven-publish` 로 mavenLocal 발행 |
| 추가 | `shared-internal-auth/.../InternalTokenIssuer.kt` | RS256 단명 JWT 발급 |
| 추가 | `shared-internal-auth/.../InternalTokenAuthenticationFilter.kt` | `X-Internal-Auth` 검증 → `InternalAuthentication`(principal=userId:Long) |
| 추가 | `shared-internal-auth/.../InternalAuthentication.kt` | principal=Long, name=userId, callerService=iss |
| 추가 | `shared-internal-auth/.../InternalAuthProperties.kt` | `internal-auth` issuer/verifier 설정 |
| 추가 | `shared-internal-auth/.../PemKeyParser.kt` | PKCS#8 / X.509 PEM 파싱 |
| 추가 | `shared-internal-auth/.../InternalAuthAutoConfiguration.kt` | 조건부 빈(issuer/verifier) + 필터 자동등록 비활성 |
| 추가 | `shared-internal-auth/.../InternalTokenRoundTripTest.kt` | 발급↔검증 라운드트립 + aud 불일치/헤더 부재 |
| 수정 | `settings.gradle.kts`, `build.gradle.kts`(루트) | `includeBuild("shared-internal-auth")` + buildAll/testAll/cleanAll 에 추가 |
| 수정 | `gateway/build.gradle.kts` | `mavenLocal()` + shared 의존 |
| 수정 | `gateway/.../filter/HeaderInjectionFilter.kt` | 평문 헤더 주입 → 라우트별 `aud` 로 internal JWT 발급 후 `X-Internal-Auth` 주입 |
| 수정 | `gateway/.../application-local.yml` | `internal-auth.issuer`(gateway 개인키) |
| 수정 | `core-api/build.gradle.kts` | `mavenLocal()` + security starter + shared 의존 |
| 추가 | `core-api/.../shared/config/SecurityConfig.kt` | 내부토큰 필터 보안체인 결합, `/actuator/health` 외 인증 필수 |
| 수정 | `core-api/.../iam/IamApiRestClientConfig.kt` | Authorization/X-User-Id relay 제거 → core-api 발급 internal JWT 주입 |
| 수정 | `core-api/.../web/ErrorCaseController.kt`, `ErrorCaseAttachmentController.kt` | `@RequestHeader("X-User-Id")` → `@AuthenticationPrincipal userId: Long` |
| 수정 | `core-api/.../application-local.yml` | `internal-auth` verifier(gateway 공개키) + issuer(core-api 개인키) |
| 수정 | `iam-api/build.gradle.kts` | `mavenLocal()` + shared 의존 |
| 추가 | `iam-api/.../security/InternalTokenResourceServerConfig.kt` | `BearerTokenResolver`(X-Internal-Auth) + `MultiIssuerJwtDecoder`(gateway/core-api 공개키, aud=iam-api) |
| 수정 | `iam-api/.../security/SecurityConfig.kt` | resource server 에 내부토큰 resolver/decoder 결합 |
| 삭제 | `iam-api/.../jwt/JwtDecoderConfig.kt` | 사용자 JWT(HMAC) resource-server 검증기 — token relay 제거로 불필요 |
| 수정 | `iam-api/.../application-local.yml`, `src/test/resources/application-test.yml` | `internal-auth` verifier(gateway+core-api 공개키) |

### 검증
- `shared-internal-auth`: 단위 테스트(라운드트립/aud 불일치/헤더부재) 통과.
- `iam-api` `contextLoads` (H2, test profile): 통과 — 커스텀 resolver/decoder + HMAC 디코더 삭제 후 정상 기동.
- `core-api` `contextLoads` (Postgres, local profile): 통과 — 보안체인 + issuer/verifier 빈 + 컨트롤러 마이그레이션 정상.
- `gateway`: 컨텍스트 초기화 정상(개인키 PEM 파싱 포함). 단독 부팅은 8000 포트 선점(기존 인스턴스)으로 web server 바인드만 실패 — 빈 생성은 모두 성공.

### 후속 과제 (이번 범위 밖)
- `dev/stg/prod` 프로파일에 `internal-auth` 키를 ENV 로 주입하도록 `application-{env}.yml` 보강 필요(현재 미설정 시 issuer/verifier 빈 부재로 기동 실패). 로컬 baseline 만 우선 완성.
- 게이트웨이가 downstream 으로 원본 `Authorization` 헤더를 계속 forward 하는지(스트립 여부) 점검 — 현재는 무해하나 명시적 제거 권장.
- 키 배포를 properties 인라인 → JWKS endpoint 로 전환(메쉬 도입 전 중간 단계).



**요청 한 줄**: 워크스페이스 초대 시 실제로 메일이 안 가던 상황 해결 — invite → email → accept 전체 플로우 동작.

**한 줄 요약**: `LoggingInvitationEmailSenderAdapter` (콘솔 로그만) 외에 `SmtpInvitationEmailSenderAdapter` 신설. `JavaMailSender` + `classpath:email/invitation.html` 템플릿(placeholder 치환, HTML, UTF-8) 으로 MIME 메일 발송. `iam.email.provider` (smtp/logging) 로 두 어댑터를 `@ConditionalOnProperty` 분기 — 동시에 하나만 빈 등록되어 의존성 충돌 없음. 로컬에선 docker-compose 에 추가한 **MailHog** 컨테이너(1025 SMTP / 8025 web UI) 가 수신, 외부로 실제 메일 안 나감.

> 도메인/JPA/use case/REST 컨트롤러는 직전 작업에서 이미 완성되어 있었음. 누락된 단 한 부분은 "실제 SMTP 발송 어댑터" 였고 이번에 채움. accept flow (`AcceptInvitationUseCase` + `POST /api/v1/invitations/accept`) 도 그대로 동작.

### 설계 결정
- **두 어댑터 + @ConditionalOnProperty 분기**: `iam.email.provider=smtp` → SMTP 어댑터, `iam.email.provider=logging` (또는 미지정) → Logging 어댑터. `matchIfMissing = true` 로 안전 default 보장 (테스트/CI 환경에서 SMTP 강제 X).
- **포트 시그니처 유지**: `InvitationEmailSenderPort.send(toEmail, workspaceName, invitedByDisplayName, role, acceptUrl)` 그대로. `expiresAt` 은 어댑터 내부에서 모르므로 템플릿에 "관리자 안내 참고" 로 임시 표기 + KDoc 에 port 확장 TODO 명시.
- **템플릿은 resource 파일 + `{{key}}` 치환**: Thymeleaf 등 추가 의존성 X. `classpath:email/invitation.html` 한 번만 lazy load 후 멀티스레드 재사용. placeholder 값은 `escapeHtml()` 로 사용자 입력(워크스페이스명/inviter 이름) XSS 차단.
- **profile 별 발송 정책**:
  - `local`: `provider=smtp` + spring.mail = MailHog (1025, auth false, starttls false) → 외부 안 나감
  - `dev/stg/prod`: `provider=smtp` + spring.mail.* 환경변수 (`IAM_MAIL_HOST` / `IAM_MAIL_USERNAME` / `IAM_MAIL_PASSWORD`) → SES/SendGrid 등 어떤 SMTP 든 가능
- **발송 실패 시**: `MailException` 로깅 후 그대로 throw. 현재 `InviteMemberUseCase` 가 `@Transactional` 이므로 invitation row 가 롤백됨 — "메일은 안 갔는데 토큰만 DB 에 있는" 불일치 차단.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `iam-api/build.gradle.kts` | `spring-boot-starter-mail` 추가 |
| 수정 | `iam-api/docker-compose.yml` | MailHog 서비스 추가 (포트 1025/8025) |
| 추가 | `iam-api/src/main/kotlin/.../workspace/config/InvitationEmailProperties.kt` | `iam.email` prefix ConfigurationProperties (provider/fromAddress/fromName/subjectPrefix) + 등록 클래스 |
| 추가 | `iam-api/src/main/kotlin/.../workspace/infrastructure/email/SmtpInvitationEmailSenderAdapter.kt` | `JavaMailSender` 기반 어댑터, `MimeMessageHelper` (HTML, UTF-8), 템플릿 lazy load, escapeHtml, role 한글 라벨 매핑 |
| 추가 | `iam-api/src/main/resources/email/invitation.html` | 반응형 인라인 CSS, "초대 수락하기" 버튼, fallback 링크, 만료 안내 |
| 수정 | `iam-api/src/main/kotlin/.../workspace/infrastructure/email/LoggingInvitationEmailSenderAdapter.kt` | `@ConditionalOnProperty(name=iam.email.provider, havingValue=logging, matchIfMissing=true)` |
| 수정 | `iam-api/src/main/resources/application.yml` | `iam.email.*` 공통 베이스 (ENV 변수 default = logging / noreply@error-archive.local / Error Archive) |
| 수정 | `iam-api/src/main/resources/application-local.yml` | `spring.mail` MailHog 설정 + `iam.email.provider=smtp` |
| 수정 | `iam-api/src/main/resources/application-dev.yml` | `spring.mail.*` ENV placeholder (`IAM_MAIL_HOST`/`USERNAME`/`PASSWORD`) + `iam.email.provider=smtp` |
| 수정 | `iam-api/src/main/resources/application-stg.yml` | 동일 (stg 도메인) |
| 수정 | `iam-api/src/main/resources/application-prod.yml` | 동일 (prod 도메인) |

### End-to-End 동작 확인

```bash
# 1) 인프라 띄우기
cd iam-api
docker compose up -d                  # iam-postgres + iam-mailhog
./gradlew bootRun                     # local profile

# 2) MailHog web UI 미리 열어두기
open http://localhost:8025

# 3) JWT 받기 (Github OAuth 또는 local secret 직접 서명) → 워크스페이스 생성
TOKEN="..."  # 발급 받은 access token
WS_ID=$(curl -s -X POST http://localhost:8080/api/v1/workspaces \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"My WS"}' | jq -r '.id')

# 4) 이메일 초대
curl -s -X POST http://localhost:8080/api/v1/workspaces/$WS_ID/invitations \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"type":"EMAIL","email":"invitee@example.com","role":"WRITE"}'

# → MailHog UI (http://localhost:8025) 에서 "My WS 워크스페이스 초대" 메일 확인
#   본문의 "초대 수락하기" 버튼 = http://localhost:5173/invitations/{rawToken}

# 5) 초대 받은 사람이 같은 이메일(invitee@example.com)로 로그인 후
curl -s "http://localhost:8080/api/v1/invitations/preview?token={raw}" \
  -H "Authorization: Bearer $INVITEE_TOKEN"           # 200 OK + workspace 정보

curl -s -X POST http://localhost:8080/api/v1/invitations/accept \
  -H "Authorization: Bearer $INVITEE_TOKEN" -H "Content-Type: application/json" \
  -d '{"token":"{raw}"}'                              # 200 OK → 멤버 추가됨
```

### 후속 작업 / 남은 TODO
- **port 시그니처 확장**: `InvitationEmailSenderPort.send(...)` 에 `expiresAt: Instant` 추가하면 본문의 만료시각이 "관리자 안내 참고" → 실제 KST 시각으로 표시 가능. 현재는 InviteMemberUseCase 에서 expiresAt 계산하므로 단순 인자 추가만 필요.
- **prod transactional email**: SES SMTP 자격증명 발급 + `IAM_MAIL_HOST=email-smtp.ap-northeast-2.amazonaws.com` 같은 식으로 ENV 주입. 코드 변경 0.
- **메일 발송 실패 시 재시도/큐잉**: 현재 `MailException` → 즉시 throw → invitation 롤백. 대량 운영 시엔 outbox 패턴으로 분리 검토.

---

## 2026-05-12 19:22:34 KST · core-api · feat: 첨부 다운로드 인가 (Preview 시나리오 + 소유권)

**요청 한 줄**: 다운로드 endpoint 가 헤더만 받고 검증 안 하던 상태 해소.

**한 줄 요약**: `GET /api/v1/error-attachments/{markerId}` 에 인가 추가. 케이스 미연결 첨부(=Preview 시나리오) 는 **업로드한 본인만**, 케이스 연결 후엔 **ErrorCase ownerUserId 만** 다운로드 가능. 위반 시 403.

### 설계 결정
- **두 갈래 인가**: 첨부의 `errorCaseId` 가 null 이면 GitHub Write/Preview 탭의 Preview 렌더 시나리오로 보고 `uploadedByUserId == requesterUserId` 만 검사. 케이스 제출되어 `errorCaseId` 가 박힌 후엔 케이스 소유권으로 전환. → 작성 페이지의 라이브 프리뷰가 자연스럽게 동작하면서 타인 markerId 추측 다운로드는 차단.
- **`ownerUserId` projection 전용 쿼리**: 매 다운로드마다 ErrorCase aggregate 전체를 로드하지 않도록 `ErrorCaseJpaRepository.findOwnerUserIdById` 를 `@Query` 로 명시 (`SELECT e.ownerUserId FROM ErrorCaseEntity e WHERE e.id = :id`). 포트는 `ErrorCaseRepositoryPort.findOwnerUserIdById(errorCaseId): Long?` 만 노출.
- **Attachment VO 에 errorCaseId 노출**: 기존엔 JPA entity 만 알고 도메인 VO 는 몰랐던 필드 (`Long? = null`). 다운로드 인가 분기를 위해 필요. defaulted nullable 이라 기존 호출처 영향 없음.
- **예외 매핑**: `AttachmentDownloadForbiddenException` 신설 → 컨트롤러에서 `ResponseStatusException(403)`. `AttachmentNotFoundException` / `AttachmentFileNotFoundException` (404) 와 의미 구분.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `core-api/src/main/kotlin/.../domain/model/vo/Attachment.kt` | `errorCaseId: Long? = null` 필드 추가 |
| 수정 | `core-api/src/main/kotlin/.../infrastructure/jpa/adapter/AttachmentRepositoryAdapter.kt` | `toDomain()` 에 `errorCaseId` 매핑 추가 |
| 수정 | `core-api/src/main/kotlin/.../application/port/ErrorCaseRepositoryPort.kt` | `findOwnerUserIdById(errorCaseId): Long?` 추가 |
| 수정 | `core-api/src/main/kotlin/.../infrastructure/jpa/ErrorCaseJpaRepository.kt` | `@Query` projection 메서드 추가 |
| 수정 | `core-api/src/main/kotlin/.../infrastructure/jpa/adapter/ErrorCaseRepositoryAdapter.kt` | 포트 구현 위임 |
| 수정 | `core-api/src/main/kotlin/.../application/usecase/DownloadAttachmentUseCase.kt` | `invoke(markerId, requesterUserId)` 시그니처 변경, `authorize()` private 메서드 신설, `AttachmentDownloadForbiddenException` 정의 |
| 수정 | `core-api/src/main/kotlin/.../presentation/web/ErrorCaseAttachmentController.kt` | `userId` 를 use case 에 전달 (unused warning 해소), `Forbidden` → 403 매핑, 옛 잘못된 NOTE 주석 삭제 후 정확한 KDoc 으로 교체 |

### 동작 확인 시나리오
| 케이스 | 기대 응답 |
|---|---|
| 본인이 업로드한 PENDING 첨부 다운로드 (작성 페이지 Preview) | 200 |
| 타인의 PENDING 첨부 markerId 추측 다운로드 | 403 |
| 본인이 소유한 케이스의 첨부 다운로드 (조회 페이지) | 200 |
| 타인 소유 케이스의 첨부 다운로드 | 403 |
| 첨부의 errorCaseId 가 가리키는 ErrorCase 가 사라진 경우 (이론상 발생 X) | 403 |
| 존재하지 않는 markerId | 404 |
| DB 에는 있는데 디스크 파일이 사라진 경우 | 404 |

### 남은 TODO
- **고아 첨부 정리 (B+D)**: 다음 작업. `AttachmentEntity` 에 `status: PENDING/ATTACHED` 컬럼 추가 + `CreateErrorCaseUseCase` 끝부분에 사용자 본인의 미참조 PENDING 즉시 삭제 + `@Scheduled` TTL 잡으로 N 시간 경과 PENDING 회수.
- **markerId 충돌 재시도**: `RandomMarkerIdGeneratorAdapter` 에 `existsByMarkerId` 체크 + 재시도 (32 비트 충돌 대비).

---

## 2026-05-12 18:25:31 KST · core-api · feat: 첨부 업로더 추적 + 케이스 묶기 인가 — Breaking

**요청 한 줄**: 직전 다운로드 PR 리뷰에서 발견된 "즉시 보강" 항목 2개 처리 — 업로더 정보 미저장, 케이스 연결 시 인가 미검증.

**한 줄 요약**: `AttachmentEntity` 에 `uploaded_by_user_id` / `uploaded_at` 컬럼(둘 다 NOT NULL) 신설하고 도메인 `Attachment` VO 에 그대로 반영. 업로드 컨트롤러가 받던 `X-User-Id` 가 비로소 use case → entity 까지 흘러서 저장됨. `CreateErrorCaseUseCase.resolveAttachments` 에 "각 첨부의 `uploadedByUserId == command.userId` 인지" 검증 추가 — 타인 업로드 markerId 를 자기 케이스에 endpiece 해서 묶는 우회를 차단.

### 설계 결정
- **NOT NULL 강제**: `uploadedByUserId` / `uploadedAt` 둘 다 `nullable = false`. 옛 로우(uploader 모름)는 능동적으로 처리 안 함 — 그 자체가 자료 누락이며 dev 단계에서는 재업로드가 답.
- **uploadedAt 은 갱신하지 않음**: `AttachmentRepositoryAdapter.save` 의 update 경로에서 `uploadedByUserId` / `uploadedAt` 은 의도적으로 제외. 한 번 박힌 업로드 사실은 변경되지 않는다는 invariant 유지.
- **인가 위치는 application layer**: `resolveAttachments(markerIds, ownerUserId)` 안에서 검증. controller 가 아니라 use case 에 두는 이유는, 같은 정책이 향후 PATCH/리액션 등 다른 경로에서도 재사용되기 때문.
- **인덱스 추가**: `ix_attachment_uploader_orphan (uploaded_by_user_id, error_case_id)` — 향후 "내 미사용 첨부" 조회와 고아 TTL 정리 잡 모두에 활용 예정.

### Breaking
- **DB 스키마 변경**: NOT NULL 컬럼 2개가 기존 `error_case_attachment` 테이블에 추가됨. `ddl-auto: update` 가 빈 테이블에는 잘 적용되지만 **로우가 남아 있으면 ALTER TABLE 실패**. 로컬에서 직전 업로드 데이터가 남아 있으면 다음 중 하나 필요:
  ```sql
  -- (택1) 가장 깔끔: 첨부 자체 비움
  TRUNCATE TABLE error_case_attachment RESTART IDENTITY CASCADE;

  -- (택2) 옛 로우 살리기: 컬럼 미리 추가 + 기본값 채우고 NOT NULL
  ALTER TABLE error_case_attachment ADD COLUMN uploaded_by_user_id BIGINT;
  ALTER TABLE error_case_attachment ADD COLUMN uploaded_at        TIMESTAMP;
  UPDATE error_case_attachment SET uploaded_by_user_id = 0, uploaded_at = NOW()
    WHERE uploaded_by_user_id IS NULL;
  ALTER TABLE error_case_attachment ALTER COLUMN uploaded_by_user_id SET NOT NULL;
  ALTER TABLE error_case_attachment ALTER COLUMN uploaded_at        SET NOT NULL;
  ```
  직전 PR 에서 `storageUrl` 형식도 이미 깨졌으므로 (택1) 추천.
- **API 의미 변경 — 인가 추가**: A 가 업로드한 markerId 를 B 가 `attachmentMarkerIds` 에 넣어 케이스를 만들면 이제 400 (`IllegalArgumentException`). 기존엔 통과했음. 단일 사용자 로컬 테스트엔 영향 없음.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `core-api/src/main/kotlin/.../infrastructure/jpa/entity/AttachmentEntity.kt` | `uploadedByUserId: Long`, `uploadedAt: Instant` 컬럼(NOT NULL) + `ix_attachment_uploader_orphan` 인덱스 |
| 수정 | `core-api/src/main/kotlin/.../domain/model/vo/Attachment.kt` | 도메인 VO 에 동일 필드 + `require(uploadedByUserId > 0)` |
| 수정 | `core-api/src/main/kotlin/.../infrastructure/jpa/adapter/AttachmentRepositoryAdapter.kt` | toDomain / 신규 insert 시 두 필드 매핑. update 경로에서는 보존 (재할당 X) |
| 수정 | `core-api/src/main/kotlin/.../application/command/CreateAttachmentCommand.kt` | `uploaderUserId: Long` 필드 + equals/hashCode 반영 |
| 수정 | `core-api/src/main/kotlin/.../presentation/web/ErrorCaseAttachmentController.kt` | upload 의 `X-User-Id` 를 command 에 전달 (기존엔 받기만 하고 버려짐) |
| 수정 | `core-api/src/main/kotlin/.../application/usecase/CreateAttachmentUseCase.kt` | Attachment 생성 시 `uploadedByUserId = command.uploaderUserId`, `uploadedAt = Instant.now()` 세팅 |
| 수정 | `core-api/src/main/kotlin/.../application/usecase/CreateErrorCaseUseCase.kt` | `resolveAttachments(markerIds, ownerUserId)` 시그니처 변경 + uploader != ownerUserId 인 첨부 발견 시 즉시 reject |

### 후속 작업 / 남은 TODO
- **다운로드 인가** (단기): `GET /api/v1/error-attachments/{markerId}` 가 아직 헤더만 받고 검증 X. ErrorCase 연결 후엔 ownerUserId, 연결 전엔 uploadedByUserId 기준으로 체크 필요. 현재 컨트롤러의 `userId` 파라미터가 unused warning 으로 떠 있는 상태.
- **고아 TTL 잡** (단기): `error_case_id IS NULL AND uploaded_at < now() - 24h` 인 첨부 + 디스크 파일 정리. 새로 추가한 `ix_attachment_uploader_orphan` 인덱스 활용.
- **markerId 충돌 재시도** (중기): 32 비트 = 약 9.3k 업로드부터 1% 충돌. `RandomMarkerIdGeneratorAdapter` 에 `while (exists) regenerate` 루프 + 최대 3회 limit.

---

## 2026-05-12 17:16:54 KST · core-api · feat: 첨부 파일 다운로드 endpoint (로컬 FS) — Breaking

**요청 한 줄**: 로컬 FS 업로드는 검증됐고, 이제 다운로드 기능도 구현.

**한 줄 요약**: `GET /api/v1/error-attachments/{markerId}` 신설. `markerId` 로 첨부 메타데이터(DB) + 파일 bytes(LocalFS) 조회 → `Content-Type` / `Content-Disposition` / `Content-Length` 세팅하여 응답. `?download=true` 이면 `attachment` disposition, 기본은 `inline`. `X-User-Id` 헤더 요구 (게이트웨이 정책과 일관).

### 설계 결정
- **URL 형식**: 기존 `<publicBaseUrl>/<shard>/<safeName>` 형식 폐기. `<publicBaseUrl>/<markerId>` 로 통일 — 저장소 내부 구조(shard, safeName) 노출 제거. S3 전환 시 동일 URL 패턴 유지하면서 어댑터만 교체 가능.
- **포트 추상화**: `AttachmentStoragePort.load(markerId, fileName): ByteArray` 추가. LocalFS 는 디스크 layout `<storagePath>/<shard>/<markerId>__<safeName>` 그대로 두고 URL 만 분리.
- **에러 매핑**: `AttachmentNotFoundException`(DB 없음) / `AttachmentFileNotFoundException`(디스크 없음) 모두 컨트롤러에서 `ResponseStatusException(404)` 으로 변환.
- **트랜잭션**: 다운로드 use case 는 `@Transactional(readOnly = true)`.

### Breaking
- 기존에 업로드되어 DB 에 저장된 `storageUrl` (`<publicBaseUrl>/<shard>/<safeName>` 형식) 은 더 이상 유효하지 않음 → 로컬 테스트 데이터 재업로드 필요.
- `application-local.yml` 의 `public-base-url` 도 `http://localhost:8081/api/v1/error-attachments` 로 변경 (서버 자체가 다운로드 서빙하므로 `/files` 정적 경로 폐기).

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `core-api/src/main/kotlin/.../application/port/AttachmentStoragePort.kt` | `load(markerId, fileName): ByteArray` 추가 |
| 수정 | `core-api/src/main/kotlin/.../infrastructure/storage/LocalFileSystemAttachmentStorageAdapter.kt` | `load()` 구현, `resolvePath()` 추출, `storageUrl` = `<base>/<markerId>` 로 변경, `AttachmentFileNotFoundException` 정의 |
| 수정 | `core-api/src/main/kotlin/.../application/port/ErrorCaseAttachmentRepositoryPort.kt` | `findByMarkerId(markerId): Attachment?` 추가 |
| 수정 | `core-api/src/main/kotlin/.../infrastructure/jpa/adapter/AttachmentRepositoryAdapter.kt` | `findByMarkerId()` 구현 |
| 추가 | `core-api/src/main/kotlin/.../application/usecase/DownloadAttachmentUseCase.kt` | DB 메타 조회 + storage.load() 위임, `Result(markerId, fileName, contentType, size, bytes)` 반환. `AttachmentNotFoundException` 정의 |
| 수정 | `core-api/src/main/kotlin/.../presentation/web/ErrorCaseAttachmentController.kt` | `GET /{markerId}` 추가, `MediaTypeFactory` 폴백, `ContentDisposition` (UTF-8 filename) |
| 수정 | `core-api/src/main/resources/application.yml` | 기본 `public-base-url` → `http://localhost:8080/api/v1/error-attachments` |
| 수정 | `core-api/src/main/resources/application-local.yml` | local `public-base-url` → `http://localhost:8081/api/v1/error-attachments` |

### 후속 작업 / TODO
- 인가 체크: 첨부가 연결된 ErrorCase 의 `ownerUserId == X-User-Id` 검증은 추후 (현재는 markerId 만 알면 누구나 다운로드 가능).
- S3 어댑터: `load()` 에서 signed URL 리다이렉트 또는 stream proxy 중 선택.
- `core-api/docs/core-api-implementation.md` 의 5.2 절 응답 예시 (`storageUrl` 형식) 및 12.1 TODO 항목 갱신은 다음 문서 작업에서 일괄 반영.

---

## 2026-05-11 22:00:00 KST · core-api · docs: core-api-implementation.md (구현 상세 문서)

**요청 한 줄**: core-api 의 구현 내용을 한눈에 알 수 있는 문서.

**한 줄 요약**: `core-api/docs/core-api-implementation.md` 신설. (0) TL;DR 표 (1) 책임/비책임 + 게이트웨이 패턴 결과 (2) 기술 스택 (3) 헥사고날 디렉터리 구조 (4) 도메인 모델 (ErrorCase / Snapshot / CodeSnippet / Attachment / Severity / Status) (5) API 엔드포인트 명세 + 응답 예시 (6) CreateErrorCaseUseCase 의 7단계 흐름 (7) iam-api 통합 (token relay + CircuitBreaker, 4xx/5xx 처리 구분) (8) DB 스키마 (9) 운영/실행 3단계 (10) 환경 변수 (11) CircuitBreaker 매트릭스 (12) 알려진 한계 + TODO + 인가 검증 패턴 + 운영 전환 (13) 코드 레퍼런스 매핑 (14) 한 줄 요약 (15) End-to-end 사용 예시 (Python JWT 발급 + 첨부 업로드 + 케이스 생성 curl).

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `core-api/docs/core-api-implementation.md` | core-api 구현 상세 문서 |

---

## 2026-05-10 14:00:00 KST · multi(core-api/iam-api/gateway) · feat: 외부 호출 CircuitBreaker + Retry-After 헤더

**요청 한 줄**: 빠진 caller-side CircuitBreaker 보강 + 게이트웨이 Fallback 응답 표준화.

**한 줄 요약**:
- core-api → iam-api 호출(`IamWorkspaceQueryAdapter`) 에 CB 추가. 5xx/timeout 시 `existsWorkspaceForUser` 는 `IamApiUnavailableException` throw, `getAvailableWorkspaces` 는 emptyList degraded. 4xx(403/404) 는 비즈니스 결과로 CB 카운트 제외.
- iam-api → GitHub OAuth 호출(`GithubProfileFetcher.exchangeCodeForToken / fetchUser / fetchPrimaryVerifiedEmail`) 에 CB 추가. 동일 `github` CB 인스턴스 공유로 충분한 샘플 누적. emails 조회 fallback 은 기존 best-effort 패턴 유지(null).
- gateway `FallbackController` 응답에 `Retry-After: 30` 헤더 + body `retryAfterSeconds: 30` 추가. RFC 9110 §10.2.3 준수, ResilienceConfig 의 `waitDurationInOpenState(30s)` 와 일치 — 클라이언트 retry 시점에 CB 가 HALF_OPEN 으로 전이될 가능성 최대화.

### 정책 (3개 서비스 공통)
- 슬라이딩 윈도우 20건 / 실패율 50% 시 OPEN
- OPEN 30초 후 HALF_OPEN, 5건 시도
- minimumNumberOfCalls 10 (트래픽 적을 때 오판 방지)
- TimeLimiter: gateway 10s, core-api/iam-api 5s (caller 가 먼저 끊어야 thread 회수 빠름)

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `gateway/src/main/kotlin/.../filter/FallbackController.kt` | `Retry-After: 30` 헤더 + body 에 `retryAfterSeconds` |
| 수정 | `core-api/build.gradle.kts` | Spring Cloud BOM `2025.1.1` + `spring-cloud-starter-circuitbreaker-resilience4j` |
| 추가 | `core-api/src/main/kotlin/.../shared/config/ResilienceConfig.kt` | 기본 CB/TimeLimiter 정책 (window 20, 50%, 30s, 5s timeout) |
| 수정 | `core-api/src/main/kotlin/.../infrastructure/iam/IamWorkspaceQueryAdapter.kt` | `cb.run` 으로 감쌈, 4xx 는 CB 외부에서 처리, fallback 은 throw `IamApiUnavailableException` 또는 emptyList |
| 수정 | `iam-api/build.gradle.kts` | Spring Cloud BOM `2025.1.1` + circuitbreaker-resilience4j |
| 추가 | `iam-api/src/main/kotlin/.../shared/config/ResilienceConfig.kt` | 동일 기본 정책 |
| 수정 | `iam-api/src/main/kotlin/.../auth/infrastructure/oauth/GithubProfileFetcher.kt` | 3개 호출 모두 같은 `github` CB 로 감쌈, fallback 은 `GithubUnavailableException` (token/user) 또는 null (emails) |

### 빌드 검증
- core-api: ✅ compileKotlin
- iam-api: ✅ compileKotlin
- gateway: ✅ compileKotlin

### CB 매트릭스 (요약)
| 호출자 | 대상 | CB 이름 | 적용 위치 |
|-------|-----|--------|----------|
| Browser | gateway | (없음, 클라이언트 책임) | - |
| gateway | iam-api | `iamApiCB` | yml SCG filter |
| gateway | core-api | `coreApiCB` | yml SCG filter |
| core-api | iam-api | `iamApi` | `IamWorkspaceQueryAdapter` 람다 |
| iam-api | GitHub | `github` | `GithubProfileFetcher` 람다 |

### 후속 작업
- `IamApiUnavailableException` / `GithubUnavailableException` 을 ProblemDetail 503 으로 매핑하는 ExceptionHandler
- GitHub 의 `bad_verification_code` 같은 비즈니스 에러를 CB `ignoreException` 으로 분리 (현재는 실패로 카운트됨)
- 메트릭 대시보드 (`resilience4j.circuitbreaker.state{name="iamApi|github"}`) 구성

---

## 2026-05-08 11:00:00 KST · gateway · docs: gateway-implementation.md (구현 상세 문서)

**요청 한 줄**: 게이트웨이 구현 결과를 자세히 문서로 남겨달라.

**한 줄 요약**: `gateway/docs/gateway-implementation.md` 신설. (1) 책임 / 비책임 (2) 기술 스택 선택 근거 (3) 디렉터리 구조 (4) 8개 Kotlin 파일 + yml 의 책임/구현 코드 인용 (5) 요청 처리 흐름 (Filter Chain Order) (6) 보안 모델 (secret 공유 / Defense-in-Depth / 헤더 신뢰 모델) (7) Resilience 정책 상세 (CircuitBreaker 상태 머신 / Retry / TimeLimiter) (8) CORS (9) 운영·모니터링 (actuator / Resilience4j 메트릭) (10) 빌드/실행 (11) 알려진 함정 (Spring Cloud 버전 매트릭스, POST retry 의 함정, secret 공유 한계) (12) Phase 별 향후 로드맵 (13) 코드 위치 레퍼런스 표.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `gateway/docs/gateway-implementation.md` | 게이트웨이 구현 상세 문서 |

---

## 2026-05-07 23:35:00 KST · gateway · refactor: plain MVC → Spring Cloud Gateway Server MVC + Resilience4j 이전

**요청 한 줄**: 직접 구현한 proxy/JWT 필터를 표준 도구로 갈아엎고 CircuitBreaker/Retry/CORS 정식 도입.

**한 줄 요약**: Spring Cloud Gateway Server MVC (servlet 기반 SCG, Spring Cloud `2025.1.1` — Boot 4.0 매칭 release) 도입. 직접 구현했던 ProxyController · JwtAuthFilter · RouteProperties · HeaderMutatingRequestWrapper 일부 삭제. Spring Security 의 `oauth2ResourceServer.jwt` 가 JWT 검증 단독 책임을 가지고, `HeaderInjectionFilter` 가 SecurityContext 에서 X-User-Id/X-Roles 추출해 downstream 헤더로 주입. yml 의 `spring.cloud.gateway.mvc.routes` 로 라우트 + CircuitBreaker + Retry 선언. CORS 도 `CorsConfigurationSource` 로 정식 활성화.

### 동작 검증
- `/actuator/health` → 200
- 인증 필요 endpoint 토큰 없이 호출 → 401 (Spring Security 의 `oauth2ResourceServer`)
- CORS 헤더(`Vary: Origin`) 정상 응답

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `gateway/build.gradle.kts` | Spring Cloud BOM `2025.1.1` 추가, `spring-cloud-starter-gateway-server-webmvc` + `spring-cloud-starter-circuitbreaker-resilience4j` 의존 추가 |
| 삭제 | `gateway/.../proxy/ProxyController.kt` | SCG 가 라우팅 담당 |
| 삭제 | `gateway/.../filter/JwtAuthFilter.kt` | Spring Security `oauth2ResourceServer` 가 검증 담당 |
| 삭제 | `gateway/.../config/RouteProperties.kt` | SCG yml 이 대체 |
| 수정 | `gateway/.../config/SecurityConfig.kt` | `permitAll(...)` + `oauth2ResourceServer.jwt(decoder)` + `addFilterAfter(headerInjectionFilter, BearerTokenAuthenticationFilter)` |
| 추가 | `gateway/.../filter/HeaderInjectionFilter.kt` | SecurityContext 의 Jwt principal → X-User-Id/X-Roles/X-Token-Type 헤더 주입 |
| 유지 | `gateway/.../filter/HeaderMutatingRequestWrapper.kt` | HeaderInjectionFilter 가 사용 |
| 추가 | `gateway/.../config/CorsConfig.kt` | `CorsConfiguration` + `gateway.cors.*` properties |
| 추가 | `gateway/.../config/ResilienceConfig.kt` | 기본 CircuitBreaker (window 20, 50% threshold, OPEN 30s) + TimeLimiter 10s |
| 추가 | `gateway/.../filter/FallbackController.kt` | `/__fallback/{service}` 503 응답 (CircuitBreaker fallbackPath) |
| 수정 | `gateway/src/main/resources/application.yml` | actuator gateway endpoint 노출 |
| 수정 | `gateway/src/main/resources/application-local.yml` | `spring.cloud.gateway.mvc.routes` 정의 (iam-api / core-api) + CircuitBreaker + Retry 필터, CORS allowed origins |

### 라우트 구성 (local)
```yaml
- id: iam-api      → http://localhost:8080
  predicates: Path=/api/v1/auth/**,/api/v1/users/**,/api/v1/workspaces/**,/api/v1/invitations/**
  filters: CircuitBreaker(iamApiCB) + Retry(2회, exp backoff 50ms→500ms)

- id: core-api     → http://localhost:8081
  predicates: Path=/api/v1/error-cases/**,/api/v1/error-attachments/**
  filters: CircuitBreaker(coreApiCB) + Retry(동일)
```

### Resilience 정책
- CircuitBreaker: 슬라이딩 윈도우 20건, 실패율 50% 시 OPEN, 30초 후 HALF_OPEN, half-open 5건 시도
- TimeLimiter: 모든 호출 10초 timeout
- Retry: SERVER_ERROR (5xx) 시 2회 재시도, exponential backoff (50ms × 2^n, 최대 500ms)

### 후속 작업
- 분산 rate-limit 도입 (Redis 추가 필요)
- ProblemDetail 표준 401 응답 본문 (현재는 빈 body)
- gateway routes/predicates 를 dev/stg/prod profile yml 로 확장
- `/actuator/gateway/routes` 응답 확인 후 운영 모니터링 구성

---

## 2026-05-07 14:25:00 KST · repo · docs: 서비스 토폴로지 문서 추가 (gateway/iam-api/core-api 흐름)

**요청 한 줄**: 지금까지의 구현을 토대로 3개 서비스가 어떻게 연결되어 있고 흐름이 어떻게 진행되는지 문서화.

**한 줄 요약**: `docs/service-topology.md` 신설. (1) 큰 그림 ASCII 다이어그램 (2) 각 서비스 단일 책임 매트릭스 (3) 통신 패턴(동기 HTTP, token relay, 통신 안 하는 것) (4) 토큰 라이프사이클(login/refresh/logout) (5) 에러 케이스 생성 end-to-end 추적 (6) 책임/관심사 매트릭스 (7) 보안 모델 + 알려진 위협 (8) 운영 체크리스트 + 흔한 문제 매핑 (9) Phase 별 향후 개선.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `docs/service-topology.md` | 토폴로지/책임/통신/플로우/보안/운영 종합 문서 |

---

## 2026-05-07 14:00:00 KST · multi(gateway/core-api) · feat: API Gateway 도입 + core-api 풀 어댑터 구현

**요청 한 줄**: 빠진 어댑터 전부 구현 + MSA 인증을 API Gateway 패턴으로 전환.

**한 줄 요약**:
- 신규 모듈 `gateway/` (Spring Boot 4 + WebMVC + JDK HttpClient 프록시) — JWT 검증 단독 책임
- core-api 의 모든 인증/JWT 코드 제거. `X-User-Id` 헤더만 신뢰
- core-api 누락 어댑터 8개(JPA 엔티티/리포/어댑터 + marker/extractor/storage + iam-api RestClient/WorkspaceQueryAdapter) 구현으로 `POST /api/v1/error-cases` 가 실제로 DB 까지 닿음

### MSA 인증 흐름 (Gateway 패턴)
```
[브라우저] ─Bearer JWT─▶ [gateway:8000] ──검증 OK→ X-User-Id/X-Roles 헤더 주입
                                ├─/api/v1/auth/**           → iam-api (publicPath, 검증 skip)
                                ├─/api/v1/users/**          → iam-api
                                ├─/api/v1/workspaces/**     → iam-api
                                └─/api/v1/error-cases/**    → core-api  (X-User-Id 만 신뢰)
```
- core-api → iam-api 호출(WorkspaceQuery)은 RequestContextHolder 로 현재 요청의 X-User-Id/Authorization 을 자동 forwarding (token relay)

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `gateway/build.gradle.kts` 등 모듈 전체 | 신규 모듈 (Spring Boot 4.0.4) |
| 추가 | `gateway/src/main/kotlin/.../config/{JwtProperties,JwtDecoderConfig,RouteProperties,SecurityConfig}.kt` | HS256 검증 + Spring Security pass-through + 라우트 prefix 매핑 |
| 추가 | `gateway/src/main/kotlin/.../filter/JwtAuthFilter.kt` | publicPaths 체크 → JWT 검증 → X-User-Id/X-Roles 헤더 주입 |
| 추가 | `gateway/src/main/kotlin/.../filter/HeaderMutatingRequestWrapper.kt` | servlet 요청 헤더 추가용 wrapper |
| 추가 | `gateway/src/main/kotlin/.../proxy/ProxyController.kt` | `/**` catch-all → JDK HttpClient 로 downstream 전달, hop-by-hop 헤더 정리 |
| 추가 | `gateway/src/main/resources/application{,-local}.yml` | port 8000, public paths, 라우트 매핑 |
| 수정 | `settings.gradle.kts`, `build.gradle.kts` | `gateway` includeBuild 등록 + buildAll/testAll/cleanAll 에 포함 |
| 수정 | `core-api/build.gradle.kts` | security/oauth2-resource-server 의존성 제거 (gateway 로 이전), actuator/tracing/kotlin-logging 유지 |
| 추가 | `core-api/.../infrastructure/jpa/entity/{ErrorCaseEntity,CodeSnippetEntity,AttachmentEntity,ErrorSnapshotEmbeddable,MetaEmbeddable}.kt` | JPA 매핑 |
| 추가 | `core-api/.../infrastructure/jpa/{ErrorCaseJpaRepository,CodeSnippetJpaRepository,AttachmentJpaRepository}.kt` | Spring Data JPA |
| 추가 | `core-api/.../infrastructure/jpa/adapter/{ErrorCaseRepositoryAdapter,AttachmentRepositoryAdapter}.kt` | port 구현체 + 도메인↔엔티티 매핑 |
| 추가 | `core-api/.../infrastructure/marker/RandomMarkerIdGeneratorAdapter.kt` | 8-char hex marker |
| 추가 | `core-api/.../infrastructure/extractor/RegexErrorSnapshotExtractorAdapter.kt` | Java/Kotlin 스택트레이스 휴리스틱 파서 |
| 추가 | `core-api/.../infrastructure/storage/LocalFileSystemAttachmentStorageAdapter.kt` | 로컬 FS 저장 (운영 시 S3 어댑터로 교체) |
| 추가 | `core-api/.../infrastructure/iam/{IamApiRestClientConfig,IamWorkspaceQueryAdapter}.kt` | iam-api 호출용 RestClient + token relay interceptor + WorkspaceQueryPort 구현 |
| 추가 | `core-api/.../config/{AttachmentStorageProperties,IamApiProperties}.kt` | yml 바인딩 |
| 추가 | `core-api/.../shared/config/CoreApiConfigRegistration.kt` | `@ConfigurationPropertiesScan` 등록 |
| 수정 | `core-api/.../domain/model/ErrorCase.kt` | `ownerUserId` 필드 추가, `reconstitute()` 팩토리 |
| 수정 | `core-api/.../application/usecase/CreateErrorCaseUseCase.kt` | `ownerUserId = command.userId` 전달 |
| 수정 | `core-api/.../presentation/web/ErrorCaseAttachmentController.kt` | `@RequestHeader("X-User-Id")` 추가 |
| 추가 | `core-api/src/main/resources/application{,-local}.yml` | profile yml 분리, datasource(5433), iam-api base-url, attachment storage 경로 |
| 삭제 | `core-api/src/main/resources/application.properties` | yml 로 이전 |
| 추가 | `core-api/docker-compose.yml` | core-api 전용 PG (port 5433, iam-api 의 5432 와 충돌 회피) |

### 로컬 실행 순서
1. `cd iam-api && docker compose up -d && ./gradlew bootRun`        (8080)
2. `cd core-api && docker compose up -d && ./gradlew bootRun`       (8081)
3. `cd gateway && ./gradlew bootRun`                                 (8000)
4. 모든 호출은 `http://localhost:8000` 으로

### 후속 작업
- 검증 실패한 테스트 (`core-api`/`insight-api`)는 컨텍스트 로딩 이슈 가능성 — 별도 fix 커밋으로 처리
- `MarkerIdGeneratorPort.generate*` 충돌 시 retry (현재 실패 없음으로 가정)
- gateway 의 `routes` / `publicPaths` 를 dev/stg/prod yml 로 확장
- M2M 토큰 도입 (현재는 token relay) — service-to-service 간 명시적 인증

---

## 2026-05-06 11:30:00 KST · core-api · feat: 에러 케이스 생성 API (BlogConcept40 mockup 기반)

**요청 한 줄**: BlogConcept40.html 의 "케이스 생성" 폼을 백엔드에서 실제로 받아 처리.

**한 줄 요약**: 비어있던 도메인(`Severity` enum, `Meta.create()`, `ErrorCase.create()`)을 채우고, 누락된 `ErrorCaseRepositoryPort` 추가, `CreateErrorCaseUseCase.invoke()` 구현, `POST /api/v1/error-cases` 컨트롤러 신설. 첨부는 markerId 참조(선업로드 → /error-attachments 로 marker 발급), 스니펫은 inline 으로 받아 use case 가 markerId 부여. 인증은 `X-User-Id` 헤더로 임시 (iam-api JWT 통합 시 교체 예정).

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `core-api/src/main/kotlin/.../domain/model/vo/Severity.kt` | 빈 클래스 → S1~S4 enum (code + label) |
| 수정 | `core-api/src/main/kotlin/.../domain/model/vo/Meta.kt` | `create(workspaceId, severityCode, environment)` 팩토리 |
| 수정 | `core-api/src/main/kotlin/.../domain/model/ErrorCase.kt` | `create()` 깨진 시그니처 수정, `occurredAt` 필드 추가 |
| 추가 | `core-api/src/main/kotlin/.../application/port/ErrorCaseRepositoryPort.kt` | `save(ErrorCase)` |
| 수정 | `core-api/src/main/kotlin/.../application/command/CreateErrorCaseCommand.kt` | `paste`/`scope` non-null, `environment` 단일 String, `attachmentMarkerIds`, inner `SnippetInput` |
| 수정 | `core-api/src/main/kotlin/.../application/usecase/CreateErrorCaseUseCase.kt` | `invoke()` 구현: 워크스페이스 권한 검증 → snapshot 추출 → snippet markerId 발급 → attachment marker 검증 → ErrorCase.create → save |
| 수정 | `core-api/src/main/kotlin/.../presentation/web/dto/request/CreateErrorCaseRequest.kt` | scope/paste `@NotBlank`, title `@Size(max=200)`, `attachmentMarkerIds: List<String>`, `environment: String?` |
| 추가 | `core-api/src/main/kotlin/.../presentation/web/dto/response/CreateErrorCaseResponse.kt` | id/title/status/fingerprint/markerIds/createdAt |
| 추가 | `core-api/src/main/kotlin/.../presentation/web/ErrorCaseController.kt` | `POST /api/v1/error-cases` (201 CREATED) |

### API 명세
```
POST /api/v1/error-cases
Headers: X-User-Id: <long>
Body:
{
  "title": "[prod] 주문서 생성 API에서 커넥션 타임아웃",
  "scope": "core-api / order",
  "paste": "java.net.SocketTimeoutException: ...",
  "description": "# 문제 요약\n@snippet(abcdef12) ...",
  "snippets": [
    { "title": "OrderService#createOrder", "language": "java",
      "filePathOrClass": "...", "lineRange": "L120-L186",
      "caption": "...", "code": "..." }
  ],
  "attachmentMarkerIds": ["abc12345"],
  "workspaceId": 1,
  "severity": 2,
  "environment": "k8s / cloud-sql / ap-northeast-2",
  "occurredAt": "2026-02-25T18:10:00"
}
```

### 후속 작업 (별도 커밋)
- iam-api JWT 통합 후 `X-User-Id` 헤더 → `@AuthenticationPrincipal Jwt` 로 교체
- JPA 어댑터 (`ErrorCaseEntity` + `ErrorCaseRepositoryAdapter`) 구현 — 현재 port 만 정의됨
- `WorkspaceQueryPort` 어댑터 (iam-api 호출 또는 로컬 캐시)
- `MarkerIdGeneratorPort` / `ErrorSnaphostExtractorPort` / `AttachmentStoragePort` 어댑터
- 업데이트 / 조회 / 삭제 엔드포인트

---

## 2026-05-04 16:55:00 KST · iam-api · fix: GitHub authorize URL 빌더의 스코프 공백 인코딩 오류

**요청 한 줄**: `GithubAuthorizationUrlBuilder` 가 `"read:user user:email"` 스코프를 던질 때 `Invalid character ' ' for QUERY_PARAM` 으로 터짐.

**한 줄 요약**: `UriComponentsBuilder...build(true)` 는 "값이 이미 인코딩되어 있음"을 선언하는 옵션이라 스코프 내부 공백을 거절. `.encode().build()` 로 교체하여 빌더가 직접 URL 인코딩하도록 변경(공백 → `%20`).

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `iam-api/src/main/kotlin/org/studieojavry/iamapi/auth/infrastructure/oauth/GithubAuthorizationUrlBuilder.kt` | `.build(true)` → `.encode().build()`. |

---

## 2026-05-04 16:30:00 KST · iam-api · chore: 로깅 스택 도입 (kotlin-logging + Micrometer Tracing + logback-spring.xml)

**요청 한 줄**: iam-api 에 로그를 찍을 수 있게 의존성 추가 + 세팅.

**한 줄 요약**: Kotlin idiomatic 로깅(`kotlin-logging-jvm`) + Brave 기반 traceId/spanId MDC 자동 주입(`micrometer-tracing-bridge-brave`) + actuator. `logback-spring.xml` 신설하여 console/file appender 를 profile 별로 분리(local·dev: console only, stg·prod: console + rolling file, test: WARN). 패턴은 yml 의 `logging.pattern.console|file` 로 override 가능하고, default pattern 에 `%X{traceId:-},%X{spanId:-}` 포함되어 prod yml 의 분산 추적용 패턴 전제와 자연스럽게 맞물림.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `iam-api/build.gradle.kts` | `spring-boot-starter-actuator`, `io.micrometer:micrometer-tracing-bridge-brave`, `io.github.oshai:kotlin-logging-jvm:7.0.7` 추가. |
| 추가 | `iam-api/src/main/resources/logback-spring.xml` | Spring Boot defaults include + console appender + stg/prod rolling file appender(100MB / 30일 / 3GB cap, gzip). profile 별 root logger 분리(local·dev / stg·prod / test). |

### 사용법
```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

class Foo {
    fun bar() {
        log.info { "started" }              // lazy evaluation — disabled level 일 땐 람다 미실행
        log.debug { "value=$expensive" }
    }
}
```

### 후속 고려
- prod 에서 구조화 로그(JSON)가 필요해지면 `logstash-logback-encoder` 추가 후 stg/prod 전용 encoder 로 교체.
- request access log / 비즈니스 이벤트 로그가 필요하면 별도 logger name 으로 appender 분리.

---

## 2026-05-04 14:52:31 KST · iam-api · chore: 환경별 application.yml 분리 (local/dev/stg/prod/test)

**요청 한 줄**: 환경별 설정 가이드대로 yml 파일 분리.

**한 줄 요약**: `application.yml`(공통) + `application-{local,dev,stg,prod}.yml`(환경별) + `application-test.yml`(테스트). secret 은 환경별 정책 다르게(local 평문 placeholder · dev/stg/prod env 주입). `spring.profiles.default: local` 로 명시 안 하면 local 부팅.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `iam-api/src/main/resources/application.yml` | 공통만 남김(서비스명·jpa.open-in-view·dialect·jwt TTL·cookie 이름·workspace TTL·`spring.profiles.default: local`). datasource·secret·환경별 값 모두 제거. |
| 추가 | `iam-api/src/main/resources/application-local.yml` | docker-compose PG, dev placeholder secret(yml 평문), `ddl-auto: update`, `show-sql: true`, DEBUG 로그, cookie `secure: false`. |
| 추가 | `iam-api/src/main/resources/application-dev.yml` | dev RDS env 주입(기본값 없음 → 누락 시 즉시 실패), `ddl-auto: update`, INFO 로그, cookie `secure: true` + domain `.dev.studieo-javry.com`, actuator 노출. |
| 추가 | `iam-api/src/main/resources/application-stg.yml` | stg RDS env, **`ddl-auto: validate`**(★ Flyway 전제), Hikari 운영급(20), monitoring endpoint, `health.show-details: when-authorized`. |
| 추가 | `iam-api/src/main/resources/application-prod.yml` | prod RDS env(Secrets Manager 권장), `ddl-auto: validate`, Hikari 30, traceId/spanId 로그 패턴, actuator path `/internal/actuator`, `health.show-details: never`, `error.include-message/stacktrace/binding-errors: never`. |
| 이동/수정 | `iam-api/src/test/resources/application.yml` → `application-test.yml` | profile 기반(`@ActiveProfiles("test")`)으로 명시화. H2 in-memory(PG 호환 모드). |
| 수정 | `iam-api/src/test/kotlin/.../IamApiApplicationTests.kt` | `@ActiveProfiles("test")` 추가. |
| 수정 | `iam-api/docs/local-development.md` | 환경별 yml 구조, 프로파일 활성화 방법, 환경별 차이 매트릭스, dev/stg/prod 배포 시 필요 env 목록, 운영 도입 시 추가 작업 항목. |

### 결정 사항
- **공통 베이스 + profile 분리(패턴 A)**: 한 yml 안 multi-document 분리(패턴 B) 대신 파일 분리. 환경 추가 시 비대화 방지 + diff 친화적.
- **`spring.profiles.default: local`**: 명시 안 하면 local. dev/stg/prod 는 `SPRING_PROFILES_ACTIVE` env 로만 활성화.
- **운영용 `${ENV}` 에 기본값 없음**: env 누락 시 즉시 부팅 실패. placeholder 가 실수로 운영에 사용되는 사고 방지.
- **secret 정책 환경별**: local 은 yml 에 평문 placeholder(git 에 들어가도 OK), dev 는 env 파일/EnvironmentFile, stg/prod 는 Secrets Manager / Vault.
- **`ddl-auto`**: local/dev=`update`, stg/prod=`validate`. stg 에서 마이그레이션 정책 검증.
- **GitHub OAuth App 환경별 분리 명시**: 각 환경마다 별도 OAuth App(callback URL 고정). prod secret 이 dev 에 흘러가는 사고 방지.
- **에러 메시지 노출 정책**: prod 만 `include-message/stacktrace/binding-errors: never` 로 차단.
- **Actuator 경로**: prod 는 `/internal/actuator` 로 변경 + health 상세 차단. 외부 노출 면 축소.
- **로그 패턴**: prod 만 traceId/spanId 포함 (분산 추적 준비).

### 검증
- `./gradlew clean test` 통과 — `@ActiveProfiles("test")` + `application-test.yml` 로 H2 부팅 확인.
- 실 dev/stg/prod 부팅 검증은 배포 시 사용자 환경에서 수행.

### 차후 작업
- **Flyway 도입**: stg/prod `ddl-auto: validate` 운영을 위해 필수. `db/migration/V*__*.sql`.
- **Schema Registry / Secrets Manager 연동**: secret rotation 자동화.
- **GitHub OAuth App 환경별 등록**: 4개 OAuth App(local/dev/stg/prod) 발급 후 각 환경 env 주입.

### 관련 문서
- `iam-api/docs/local-development.md` (갱신)
- `iam-api/docs/spring-security-usage.md`

---

## 2026-05-02 18:20:16 KST · iam-api · chore: H2 → PostgreSQL 전환 (docker-compose) **Breaking**

**요청 한 줄**: 지금 H2로 동작 중인데 docker-compose로 PostgreSQL 띄워서 iam-api에 적용. application.yml 수정.

**한 줄 요약**: 운영 DB는 PostgreSQL(docker-compose)로 전환. 테스트는 H2 in-memory(PG 호환 모드)로 docker 없이 부팅 가능하도록 분리.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `iam-api/docker-compose.yml` | postgres:16-alpine, DB `iam_db`, user `iam`, port 5432, 명명된 볼륨 `iam-postgres-data`, healthcheck. dev 비밀번호는 placeholder. |
| 수정 | `iam-api/build.gradle.kts` | `runtimeOnly("org.postgresql:postgresql")` 추가. 기존 `runtimeOnly("com.h2database:h2")` → **`testRuntimeOnly`로 이동** (운영 classpath에서 H2 제거). |
| 수정 | `iam-api/src/main/resources/application.yml` | datasource를 PostgreSQL로 전환. `IAM_DB_URL` / `IAM_DB_USERNAME` / `IAM_DB_PASSWORD` env 로 덮어쓰기 가능. Hikari 풀(maximum 10, min idle 2, connection timeout 5s, max lifetime 30m) + `PostgreSQLDialect` 명시. |
| 추가 | `iam-api/src/test/resources/application.yml` | 테스트 전용 오버라이드. H2 in-memory(`MODE=PostgreSQL`) + iam.* dev placeholder 값(secret 64자 이상). docker 없이 `./gradlew test` 가능. |
| 추가 | `iam-api/docs/local-development.md` | docker compose 사용법, env 오버라이드, psql 접속, 운영 환경 준비 체크리스트. |

### 결정 사항
- **DB 분리 단위 = 마이크로서비스**: iam-api 전용 PostgreSQL 컨테이너(`iam_db`). 같은 서비스 내 BC들은 같은 DB + 테이블 prefix(`auth_*`, `workspace_*`, ...) 그대로 유지.
- **테스트는 H2 유지**: docker 의존 없이 CI/로컬에서 즉시 실행 가능. SQL 호환성을 위해 `MODE=PostgreSQL` 고정. `DATABASE_TO_LOWER=TRUE`로 PG 케이스 정합성 확보.
- **H2를 testRuntimeOnly로 이동**: 운영 classpath에서 H2 제거 → 운영에서 실수로 H2 URL을 쓰는 사고 방지.
- **dev 비밀번호 직박힘**: `iam_local_pw` placeholder. 운영은 반드시 Secrets Manager / Vault 로 주입.
- **`ddl-auto=update` 유지(현재)**: dev 편의용. 운영 도입 시 `validate` + Flyway/Liquibase로 마이그레이션 도입 예정(차후 항목).

### 마이그레이션
- 기존 H2 in-memory 데이터는 영속이 아니었으므로 마이그레이션 불필요.
- 첫 부팅 시 JPA `ddl-auto=update`가 모든 테이블을 PostgreSQL에 생성.
- 운영 도입 시: `IAM_DB_URL`/`IAM_DB_USERNAME`/`IAM_DB_PASSWORD` env 주입 + `ddl-auto=validate` 전환.

### 검증
- `./gradlew clean test` 통과 (H2 오버라이드 경유 부팅).
- `docker compose up -d` 후 `./gradlew bootRun` 으로 실 PG 연결 확인은 사용자 환경에서 수행 (Docker daemon 미실행으로 본 PR 자동 검증 불가).

### 관련 문서
- `iam-api/docs/local-development.md` (신규)
- `docs/architecture-current.md` — DB 분리 단위(마이크로서비스 단위) 정책과 일치

---

## 2026-05-01 16:40:46 KST · iam-api · docs: Spring Security 활용 레퍼런스 문서 신설

**요청 한 줄**: 현재 iam-api 구현을 바탕으로 Spring Security를 어떻게 사용하고 있는지 인증/인가 파트로 나눠 정리.

**한 줄 요약**: `iam-api/docs/spring-security-usage.md` 신설. 의존성·인증·인가·보안헤더·세션정책·예외매핑·의도적 미사용 기능·MSA 분리 시 적용·향후 개선 후보·신규 endpoint 체크리스트까지 한 곳에.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `iam-api/docs/spring-security-usage.md` | Spring Security 활용 한눈 정리. 인증(JWT 발급/검증·OAuth flow·refresh rotation·쿠키)·인가(URL 단위·@AuthenticationPrincipal·도메인 가드 WorkspaceAccess)·보안 헤더·세션·예외 매핑·미사용 기능·서비스 분리 시 키 공유 전략·개선 후보·신규 endpoint 체크리스트. |

### 결정 사항
- **분담 원칙**: 검증된 인프라(JWT 검증·필터·보안 헤더·URL 인가)는 Spring Security가, 도메인 정책(OAuth flow·refresh rotation·도메인 컨텍스트 권한)은 직접 구현해 use case/도메인에 둠.
- **stateless JWT 모델 유지**: `oauth2-client`/`formLogin`/`RememberMe`/`CSRF` 의도적 미사용. rememberMe는 refresh TTL/쿠키 정책으로 직접.
- **인가는 이중 적용**: URL 단위(`SecurityConfig.authorizeHttpRequests`) + 도메인 단위(`WorkspaceAccess.requireMember/requireAdmin`).
- **개선 후보 명시**: 커스텀 `CurrentUser` principal, `AccessDeniedHandler` 추가, RS256 마이그레이션(서비스 분리 시점), JWT 클레임에 워크스페이스 권한 포함.

### 검증
- 코드 변경 없음. 문서만.

### 관련 문서
- `iam-api/docs/spring-security-usage.md` (신규)
- `iam-api/docs/auth-implementation.md` (인증 도메인 전체 흐름)
- `iam-api/docs/auth-frontend-integration.md` (FE 통합)

---

## 2026-04-30 16:47:38 KST · repo · docs: 현재 채택 아키텍처(5-service) 문서 신설

**요청 한 줄**: 5개 서비스(core/iam/publishing/notification/insight) + 외부 REST + 내부 gRPC + Kafka 이벤트 구성으로 가정. DB 분리 단위(서비스 vs BC) 명시, 다이어그램 신규 작성.

**한 줄 요약**: `docs/architecture-current.md` 신설. DB는 마이크로서비스 단위 분리, 같은 서비스 안 BC는 같은 DB + 테이블 prefix로 소유권 표현. REST/gRPC/Kafka 통신 패턴 명시.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `docs/architecture-current.md` | 5-service 다이어그램(REST/gRPC/Kafka 표기 분리) + DB 분리 정책 + 통신 매트릭스 + 인프라 구성 + Phase 로드맵. |

### 결정 사항
- **DB 분리 단위 = 마이크로서비스**. BC 단위는 형식적 만족만 주고 운영비 4배. 진짜 격리 가치(장애·백업·스케일·DB엔진 다양화)는 서비스 단위에서만 발생.
- **같은 서비스 안 BC 격리 방법**: 테이블 prefix(`auth_*`, `workspace_*`, ...)로 소유권 표현 + 도메인 import 차단(ACL) + cross-BC는 소프트 참조(FK 제약 없음). 미래 BC 분리 시 마이그레이션 비용 최소.
- **통신 분리**:
  - 외부 클라이언트 → REST + JSON + JWT + OpenAPI 3.x
  - 내부 서비스 sync → gRPC + ProtoBuf + Schema Registry + circuit breaker + mTLS
  - 비동기 → Kafka + Outbox + idempotent consumer + Schema Registry
- **insight는 순수 컨슈머**: 다른 서비스에 동기 호출 절대 X. 자체 OLAP read 모델만.
- **gRPC는 cold path에만**: 정상 hot path는 모두 이벤트 또는 JWT 클레임으로 처리.
- **이벤트 토픽 네이밍**: `<bc>.<aggregate>.<event-name>` (예: `workspace.member.joined`).

### 검증
- 코드 변경 없음. 문서만.

### 관련 문서
- `docs/architecture-current.md` (신규, 현재 채택 5-service 안)
- `docs/architecture-overview.md` (목표 상태 8-service)
- `docs/architecture-recommendations.md` (근거·패턴·체크리스트)

---

## 2026-04-29 21:21:22 KST · repo · docs: 아키텍처 다이어그램 + 권장사항 문서 추가

**요청 한 줄**: 사용자 제시 mermaid 다이어그램에 권장사항 반영 + 권장사항을 별도 문서로 정리.

**한 줄 요약**: `docs/architecture-overview.md`(다이어그램+요약)와 `docs/architecture-recommendations.md`(근거·패턴·체크리스트) 신설.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `docs/architecture-overview.md` | mermaid 다이어그램 갱신본. timeline을 case-service 안의 BC로 통합, Solution↛Timeline 의존 제거, AuthZ는 JWT+이벤트로(매 요청 동기 RPC 회피), DB-per-service·외부의존(GitHub/FCM/Email/Object Storage)·API Gateway 책임 명시, publishing-service 내부 BC 분리(snapshot/sharelink), insight는 read-model only. 변경 사항 표 + 통신 패턴 + BC 정리 포함. |
| 추가 | `docs/architecture-recommendations.md` | Strong recommendations 7개(timeline 통합, Solution 의존 제거, AuthZ 위임, DB-per-service, 외부의존 명시, Gateway 책임, BC 분리), 적용 패턴 6개(Outbox, Idempotency, Circuit Breaker, Health Probe, OTel tracing, Schema Registry), 같은 서비스 안 BC 협력 규칙, publicId 정책, 의사결정 체크리스트, 안티패턴 7개, Phase 로드맵. |

### 다이어그램 주요 변경
- **Timeline**: 별도 service → case-service 내부 BC.
- **Solution**: timeline 동기 의존 제거. Case/fingerprint에 직접 붙음.
- **AuthZ**: `CASE→WS`, `TL→CASE` 매 요청 동기 호출 제거. JWT 클레임에 `ws:[{id, role}]` 포함 + workspace의 `MemberRoleChanged` 이벤트로 토큰 family revoke.
- **각 서비스 자체 DB 명시**: case_db, identity_db, workspace_db, ...
- **외부 의존**: GitHub OAuth, FCM/APNs, Email Provider, Object Storage. 점선 표기.
- **API Gateway 책임 노출**: JWT 검증 / Rate limit / 라우팅 / CORS / 응답 캐시.
- **publishing-service 내부 BC**: `snapshot`(불변) + `sharelink`(URL/만료/접근).
- **insight-service**: 자체 OLAP read DB 보유, 이벤트 컨슈머 only.
- **양방향 구독 추가**: identity/case도 일부 이벤트 구독(예: identity가 `MemberRoleChanged` 받아 토큰 갱신).

### 결정 사항
- **이벤트는 컨슈머 있을 때만**: 추상화/인프라를 미리 깔지 않는다. YAGNI.
- **publicId(UUID) 도입은 점진**: User/Workspace 우선, Case는 core-api 도입 시점.
- **서비스 분리 순서**: identity-service 먼저(보안 격리 가치 최대) → workspace/profile → core는 마지막.
- **Phase 진입 조건**: "분리하지 않으면 진짜 아픈 지점이 있는가"가 기준.

### 검증
- 코드 변경 없음. 문서만.

### 관련 문서
- `docs/architecture-overview.md` (신규)
- `docs/architecture-recommendations.md` (신규)
- 기존: `iam-api/docs/auth-implementation.md`, `iam-api/docs/workspace-implementation.md`

---

## 2026-04-29 20:07:15 KST · repo · chore: changelog를 모노레포 루트로 이전

**요청 한 줄**: changelog는 서비스별로 나눌 게 아니니까 iam-api 안에 두지 말고 밖으로 빼자.

**한 줄 요약**: `iam-api/docs/changelog.md` → `error-archive/docs/changelog.md`. 도입부를 멀티-서비스 범위로 재작성하고, 헤더 포맷에 `<service>` 토큰을 추가.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 이동 | `iam-api/docs/changelog.md` → `error-archive/docs/changelog.md` | 위치만 변경, 기존 항목 그대로 유지. |
| 수정 | `error-archive/docs/changelog.md` (이전된 파일) | 제목/도입부를 모노레포 전체용으로 변경. 헤더 포맷에 `<service>` 토큰 추가. 작성 가이드 갱신. |
| 수정 | `~/.claude/projects/-Users-hongjiin-Documents-2026-official-error-archive/memory/feedback_iam_changelog.md` | 메모리에 저장된 changelog 위치/적용 범위를 모노레포 루트로 갱신. |

### 결정 사항
- **위치를 모노레포 루트로**: 단일 changelog로 모든 서비스 변경을 한 줄에 시간 순으로 비교 가능하게. 서비스별 분기는 헤더의 `<service>` 토큰으로 표현.
- **이동 시 git mv 미사용**: 현재 작업 트리가 `error-archive` 루트 git 저장소가 아니라 `core-api` 등 서브 프로젝트만 git이라서 일반 `mv`로 이동.
- **기존 항목은 그대로 유지**: 과거 항목들은 모두 iam-api 작업이었다는 가정이 자연스러우므로, 도입부에 명시만 하고 항목 본문은 손대지 않음(이력 손실 방지).

### 검증
- 코드 변경 없음. 빌드 검증 불필요.

### 관련 문서
- `iam-api/docs/changelog.md` (제거됨)
- `error-archive/docs/changelog.md` (신규 위치)

---

## 2026-04-29 19:48:15 KST · iam-api · feat: 워크스페이스 BC 신설 (생성/멤버/권한/이메일·링크 초대)

**요청 한 줄**: README의 워크스페이스 도메인을 구현. 사용자가 팀 워크스페이스를 1개 이상 개설(개설자=ADMIN), 멤버 초대(이메일/링크 선택), 멤버별 권한 관리(ADMIN/WRITE/READ), 멤버 목록에서 프로필 이동. error-case/timeline 콘텐츠는 본 BC가 알면 안 됨.

**한 줄 요약**: `workspace` 바운디드 컨텍스트 신설(60+ 파일). 권한 가드/마지막 ADMIN 보호/EMAIL·LINK 초대 토큰 회수 + 이메일 일치 검증/멤버 일괄 조회 N+1 방지. 멤버 응답에 `profileHref`로 프로필 이동 경로 포함.

### 도메인 추가
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `workspace/domain/model/Workspace.kt` | 애그리거트. `name`, `settings`, `createdByUserId`. `rename`/`updateSettings`. |
| 추가 | `workspace/domain/model/WorkspaceMember.kt` | 멤버. `(workspaceId, userId, role, joinedAt)`, `changeRole`. |
| 추가 | `workspace/domain/model/WorkspaceInvitation.kt` | 초대. `(type, email?, tokenHash, role, status, expiresAt, ...)`. ADMIN 초대 거부, EMAIL/LINK 분기 검증, `accept`/`revoke`/`markExpiredIfNeeded`. |
| 추가 | `workspace/domain/model/vo/WorkspaceRole.kt` | `READ < WRITE < ADMIN`. 권한 의미 메서드 노출. |
| 추가 | `workspace/domain/model/vo/InvitationType.kt` | `EMAIL` / `LINK`. |
| 추가 | `workspace/domain/model/vo/InvitationStatus.kt` | `PENDING → ACCEPTED/REVOKED/EXPIRED`. |
| 추가 | `workspace/domain/model/vo/WorkspaceName.kt` | 1~50자 검증 value class. |
| 추가 | `workspace/domain/model/vo/WorkspaceSettings.kt` | `notificationEnabled`, `defaultTimezone`. 다른 BC 콘텐츠 정책 미포함. |

### 애플리케이션 추가
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `workspace/application/port/{WorkspaceRepositoryPort, WorkspaceMemberRepositoryPort, WorkspaceInvitationRepositoryPort}.kt` | CRUD/조회/cascade 삭제. |
| 추가 | `workspace/application/port/MemberSummaryReaderPort.kt` | auth.User 직접 노출 차단을 위한 cross-context 경계. `existsActive`, `findSummaries`, `findActiveByEmail`. |
| 추가 | `workspace/application/port/InvitationTokenGeneratorPort.kt`, `InvitationEmailSenderPort.kt` | 토큰 생성/이메일 발송. |
| 추가 | `workspace/application/command/*.kt` | `Create/UpdateWorkspaceCommand`, `ChangeMemberRoleCommand`, `InviteMemberCommand`, `AcceptInvitationCommand`. |
| 추가 | `workspace/application/usecase/WorkspaceAccess.kt` | `requireMember/requireAdmin` 권한 가드 + `AccessDeniedException`. |
| 추가 | `CreateWorkspaceUseCase` | User ACTIVE 검증 → Workspace 저장 → 작성자를 ADMIN으로 즉시 join. |
| 추가 | `UpdateWorkspaceUseCase` | ADMIN 가드 → patch (name/settings). |
| 추가 | `DeleteWorkspaceUseCase` | ADMIN 가드 → invitations/members/workspace cascade. |
| 추가 | `GetWorkspaceUseCase` | requireMember → viewerRole 포함. |
| 추가 | `ListMyWorkspacesUseCase` | userId 멤버십 → 워크스페이스 목록 + 본인 role. |
| 추가 | `ListWorkspaceMembersUseCase` | requireMember → ADMIN 우선 정렬, summary 일괄 조회로 N+1 방지. |
| 추가 | `ChangeMemberRoleUseCase` | ADMIN 가드 + 마지막 ADMIN 강등 차단. |
| 추가 | `RemoveMemberUseCase` | self leave 허용 / 그 외 ADMIN. 마지막 ADMIN 제거 차단. |
| 추가 | `InviteMemberUseCase` | ADMIN 가드, ADMIN 초대 거부, TTL 클램프, 256-bit 토큰 생성→해시 저장, EMAIL 발송 / LINK 응답 노출. |
| 추가 | `AcceptInvitationUseCase` | 토큰 해시 조회 + `markExpiredIfNeeded` + EMAIL 타입은 수락자 이메일 일치 검사 + 멤버 등록. |
| 추가 | `PreviewInvitationUseCase` | 비로그인 미리보기. workspaceName/role/만료/usable. |
| 추가 | `ListInvitationsUseCase`, `RevokeInvitationUseCase` | ADMIN 운영 API. |

### 인프라 추가
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `workspace/infrastructure/jpa/Workspace*Entity.kt + JpaRepository + Adapter` | `iam_workspace`, `iam_workspace_member`(UNIQUE `(workspace_id,user_id)`), `iam_workspace_invitation`(UNIQUE `token_hash`). settings는 `@Embeddable`. |
| 추가 | `workspace/infrastructure/token/InvitationTokenGeneratorAdapter.kt` | SecureRandom 256-bit → URL-safe Base64 no padding. |
| 추가 | `workspace/infrastructure/email/LoggingInvitationEmailSenderAdapter.kt` | 임시 로그 어댑터. SMTP 연동 시 교체. |
| 추가 | `workspace/infrastructure/membersummary/MemberSummaryReaderAdapter.kt` | `auth.UserJpaRepository.findAllById` 일괄 조회 + `findFirstByEmailIgnoreCase`. |
| 추가 | `workspace/config/WorkspaceProperties.kt` | `inviteBaseUrl`, `defaultInvitationTtlHours`(168), `maxInvitationTtlHours`(720), `maxMembersPerWorkspace`(200). |

### 프레젠테이션 추가
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `WorkspaceController.kt` | POST/GET/PATCH/DELETE `/api/v1/workspaces[/{id}]`. |
| 추가 | `WorkspaceMemberController.kt` | GET/PATCH/DELETE `/api/v1/workspaces/{id}/members[/{userId}]`, `/me` self-leave. 응답 `profileHref="/users/{userId}"`. |
| 추가 | `WorkspaceInvitationController.kt` | POST/GET/DELETE `/api/v1/workspaces/{id}/invitations[/{invId}]`, `GET /api/v1/invitations/preview`, `POST /api/v1/invitations/accept`. LINK 초대만 응답에 토큰/URL 노출. |
| 추가 | `WorkspaceExceptionHandler.kt` | `AccessDeniedException → 403`, `InvalidInvitationException → 410 Gone`. |
| 추가 | DTO: `Create/UpdateWorkspaceRequest`, `ChangeMemberRoleRequest`, `CreateInvitationRequest`, `AcceptInvitationRequest`, `WorkspaceResponse`, `WorkspaceListItemResponse`, `WorkspaceMemberResponse`, `Invitation*Response` | |

### 수정 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `auth/infrastructure/jpa/UserJpaRepository.kt` | `findFirstByEmailIgnoreCase` 추가 (workspace 초대에서 사용). |
| 수정 | `auth/infrastructure/security/SecurityConfig.kt` | `GET /api/v1/invitations/preview` permitAll에 추가. |
| 수정 | `src/main/resources/application.yml` | `iam.workspace.*` 키 추가. |

### 결정 사항 (워크스페이스 정책)
- **개설자=ADMIN 즉시 부여**: CreateWorkspaceUseCase가 한 트랜잭션에서 멤버 row를 만들어 "개설자가 비멤버" 상태가 절대 발생하지 않도록.
- **초대로 ADMIN 부여 금지**: 도메인 init + 유스케이스에서 이중 차단. ADMIN은 기존 ADMIN이 promote만 가능.
- **마지막 ADMIN 보호**: 강등/추방/self leave 모두 차단(`countByWorkspaceIdAndRole(ws, ADMIN) <= 1`).
- **EMAIL 초대의 수락자 이메일 매치**: 토큰만 알면 합류되는 것을 막기 위함. LINK 초대는 의도적으로 누구나 가능.
- **토큰 평문 미보존**: SHA-256 해시만 DB에. 평문은 발급 직후 1회만 응답/이메일에 노출.
- **TTL 클램프**: 1시간 ~ 30일. 사용자 입력은 `coerceIn`으로 강제.
- **콘텐츠 미인지**: workspace 도메인 어디에도 error-case/timeline 도메인 import 금지. settings는 BC 자체 정책만 보유.

### 마이그레이션
- 신규 테이블 3종 (`iam_workspace`, `iam_workspace_member`, `iam_workspace_invitation`).
- dev H2(`ddl-auto=update`)는 자동 생성. 운영 시 동일 스키마/인덱스 그대로 마이그레이션.
- 환경변수: `IAM_INVITE_BASE_URL`을 운영 시 FE 도메인으로 설정 필수.

### 검증
- `./gradlew clean test` 통과 (Spring Context 부팅 + 기본 테스트).

### 관련 문서
- **신규**: `docs/workspace-implementation.md` — auth-implementation.md와 동일 형식. 디렉터리/도메인/유스케이스/엔드포인트/시나리오/보안 매핑.
- TODO: `docs/auth-frontend-integration.md`에 워크스페이스 화면 흐름(생성/멤버/초대/수락) FE 통합 가이드 추가 예정.

### 차후 작업(이번 PR 범위 밖)
- 실 SMTP 어댑터(SES/SendGrid)
- `maxMembersPerWorkspace` 강제 가드(현재 프로퍼티만 정의)
- 마지막 ADMIN 보호의 `IllegalStateException`을 도메인 전용 예외로 분리해 409 Conflict 매핑
- 도메인 이벤트(`WorkspaceCreated`, `MemberJoined` 등) 발행 — noti-api / insight-api 연동
- Audit log 별도 테이블

---

## 2026-04-29 16:28:37 KST · iam-api · feat: 사용자 팔로우(GitHub 스타일) 기능 추가

**요청 한 줄**: 다른 사용자에 대한 팔로우 기능을 GitHub과 동일한 규칙으로 구현. 추후 noti-api에서 팔로우 대상 활동 알림에 활용 예정.

**한 줄 요약**: `social` 바운디드 컨텍스트 신설. 단방향·즉시·승인 없는 GitHub 스타일 팔로우. UNIQUE `(follower_id, followee_id)` + 멱등 API + 페이지네이션 + 양방향 상태(맞팔 여부) 조회.

### 채택한 팔로우 규칙 (GitHub 스타일)
| 규칙 | 채택 여부 | 비고 |
|---|---|---|
| **즉시 팔로우, 승인 없음** | ✅ | "팔로우 요청" 같은 pending 상태 없음. PUT 한 방으로 즉시 follower 등록. |
| **단방향(비대칭)** | ✅ | A→B는 B→A를 의미하지 않음. 맞팔(Mutual)은 양쪽 모두가 서로 팔로우한 결과적 상태. |
| **자기 팔로우 금지** | ✅ | 도메인 `Follow.init`에서 `require(followerId != followeeId)`. 컨트롤러에서도 400 반환. |
| **멱등** | ✅ | 동일 follower→followee를 중복 PUT해도 200, 같은 대상을 재차 DELETE해도 정상. 응답 `changed`로 실제 변동 여부 알림. |
| **공개 그래프** | ✅ | `GET /followers`, `GET /following`, `GET /follow-status`는 비로그인 가능(GitHub 동일). PUT/DELETE는 JWT 필수. |
| **차단(block) 시 자동 언팔** | ❌ | 차단 기능 미구현. 향후 추가 시 `BlockUseCase`에서 follow row 정리. |
| **비공개 계정의 팔로우 요청** | ❌ | 본 서비스는 공개 프로필 전제. 추후 `User.isPrivate` 도입 시 `FollowRequest` 도메인 추가 필요. |
| **활동 알림(activity feed)** | ⏳ 추후 | noti-api에서 구독자 그래프를 조회해 푸시. 본 PR은 follow 관계만. `FollowUserUseCase`에 도메인 이벤트(`UserFollowed`) 발행 위치만 TODO로 남김. |

### 추가 파일 (신규 social 컨텍스트)
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | `social/domain/model/Follow.kt` | 애그리거트. `(followerId, followeeId, createdAt)`, 자기 팔로우 차단 가드, `create`/`rehydrate` 분리. |
| 추가 | `social/application/port/FollowRepositoryPort.kt` | `save`, `exists`, `delete`(영향 row수>0 → true), `countFollowers`, `countFollowing`, `findFollowerIds`/`findFollowingIds` 페이지네이션. |
| 추가 | `social/application/port/UserSummaryReaderPort.kt` | auth.User를 직접 노출하지 않기 위한 cross-context 경계. `existsActive(userId)`, `findSummaries(ids)`. |
| 추가 | `social/application/command/FollowUserCommand.kt`, `UnfollowUserCommand.kt` | `(followerId, followeeId)`. |
| 추가 | `social/application/usecase/FollowUserUseCase.kt` | 자기 팔로우 거부, 대상 존재성 확인, 멱등 처리. `Result.created`로 신규 생성 여부 반환. TODO: noti-api용 도메인 이벤트 발행. |
| 추가 | `social/application/usecase/UnfollowUserUseCase.kt` | 멱등 삭제. `Result.removed`. |
| 추가 | `social/application/usecase/GetFollowStatusUseCase.kt` | counts + viewer↔target 양방향 상태 + `isMutual`/`isSelf`. viewer가 null이면 비로그인 조회. |
| 추가 | `social/application/usecase/GetFollowersUseCase.kt`, `GetFollowingUseCase.kt` | id 페이지 → 일괄 summary 조회 → 원래 정렬 보존. |
| 추가 | `social/infrastructure/jpa/FollowEntity.kt` | `iam_follow` 테이블, UNIQUE `(follower_id, followee_id)`, follower/followee 인덱스. |
| 추가 | `social/infrastructure/jpa/FollowJpaRepository.kt` | `existsBy...`, `deleteRelation`(`@Modifying` JPQL), count, id-only 페이지 쿼리(`createdAt desc, id desc`). |
| 추가 | `social/infrastructure/jpa/FollowRepositoryAdapter.kt` | `Pageable` 변환 + size 1~100 클램프. |
| 추가 | `social/infrastructure/usersummary/UserSummaryReaderAdapter.kt` | auth `UserJpaRepository.findAllById`로 일괄 조회(N+1 방지). `UserStatus.ACTIVE`만 `existsActive=true`. |
| 추가 | `social/presentation/web/FollowController.kt` | 5개 엔드포인트(아래 표). `@AuthenticationPrincipal Jwt`로 viewer 식별. |
| 추가 | `social/presentation/web/dto/response/UserSummaryResponse.kt`, `FollowListResponse.kt`, `FollowStatusResponse.kt`, `FollowActionResponse.kt` | |

### 수정 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `auth/infrastructure/security/SecurityConfig.kt` | `GET /api/v1/users/*/follow-status`, `/followers`, `/following`을 `permitAll`(method+path 매칭). PUT/DELETE는 anyRequest().authenticated()로 자연 보호. |

### 엔드포인트
| Method | Path | 인증 | 설명 |
|---|---|---|---|
| `PUT`    | `/api/v1/users/{userId}/follow` | JWT 필수 | 멱등 팔로우. self → 400. response `{followerId, followeeId, following:true, changed}`. |
| `DELETE` | `/api/v1/users/{userId}/follow` | JWT 필수 | 멱등 언팔로우. response `{..., following:false, changed}`. |
| `GET`    | `/api/v1/users/{userId}/follow-status` | 선택 | counts + 양방향 상태 + isMutual/isSelf. 비로그인이면 viewer 관련 필드는 false. |
| `GET`    | `/api/v1/users/{userId}/followers?page&size` | 비로그인 가능 | 페이지네이션. size 1~100 클램프. 최신 가입한 follower부터. |
| `GET`    | `/api/v1/users/{userId}/following?page&size` | 비로그인 가능 | 동일 정책. |

### 결정 사항
- **`social` = 별도 바운디드 컨텍스트**: User는 `auth` 소유. social은 userId(Long) 참조만 보유 + cross-context port로 표시 정보 조회. 향후 social 그래프가 분리 마이크로서비스로 떨어져 나가도 변경 폭 최소.
- **DTO 정렬은 원본 ID 페이지 순서 유지**: `findAllById`는 PK 정렬을 보장하지 않으므로 follow id 리스트 순서대로 다시 정렬. 누락된 user(예: DELETED 상태로 사라진 row)는 자동 필터링 — 향후 soft-delete 일관성 정책 도입 시 revisit.
- **size 한도 100**: 무한 스크롤 페이지 폭주 방지. 더 큰 값이 필요하면 별도 export API로 분리.
- **공개 vs 비공개**: GitHub처럼 follow 관계 자체를 공개로 취급. 향후 비공개 프로필 도입 시 viewer 권한에 따라 응답을 마스킹하는 정책 추가 필요.
- **알림 연동 자리만 마련**: `FollowUserUseCase`에 `// TODO: 도메인 이벤트(UserFollowed) 발행`만 남김. 실제 발행은 noti-api 인프라(이벤트 버스/REST)와 함께 결정.

### 마이그레이션
- 신규 테이블 `iam_follow` (id PK, follower_id, followee_id, created_at) — UNIQUE (follower_id, followee_id), 인덱스 follower/followee.
- dev H2(`ddl-auto=update`)는 자동 생성. 운영 시 동일 스키마로 마이그레이션.

### 검증
- `./gradlew clean test` 통과 (Spring Context 부팅 + 기본 테스트).

### 관련 문서
- `docs/auth-implementation.md` — 본 PR은 social 컨텍스트라 별도 추적이 필요. 추후 `docs/social-implementation.md`(또는 `auth-implementation.md`에 섹션 추가)를 만들어 동일 형식으로 정리 예정.
- `docs/auth-frontend-integration.md` — FE에서 팔로우 버튼/리스트/상태 조회 호출 패턴은 차후 social 전용 가이드 분리 검토.

---

## 2026-04-29 16:05:51 KST · iam-api · feat: 마이페이지 프로필 수정 + bio + rememberMe 자동 로그인

**요청 한 줄**: 마이페이지에서 displayName/avatarUrl 수정 가능, bio(자기소개) 추가, 로그인 시 rememberMe 체크박스 → 브라우저별 자동 로그인, 사용자가 직접 로그아웃하면 비활성화.

**한 줄 요약**: `PATCH /api/v1/users/me` 신설, `User.bio` 추가, refresh token에 `rememberMe` 보존하여 회전 시 정책 유지, rememberMe=false면 세션 쿠키 + 짧은 TTL.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `auth/domain/model/User.kt` | `bio: String?` 필드 추가(<=280자, trim+blank→null 정규화). `changeDisplayName`, `changeBio` 메서드 추가. avatarUrl 검증에 `http(s)://` prefix 강제. 길이 상수(`DISPLAY_NAME_MAX_LENGTH`, `AVATAR_URL_MAX_LENGTH`, `BIO_MAX_LENGTH`) 노출. |
| 수정 | `auth/domain/model/RefreshToken.kt` | `rememberMe: Boolean` 필드 추가. `issue()` / `rehydrate()` 시그니처 확장. |
| 수정 | `auth/infrastructure/jpa/UserEntity.kt` | `bio VARCHAR(280)` 컬럼 추가. |
| 수정 | `auth/infrastructure/jpa/RefreshTokenEntity.kt` | `remember_me BOOLEAN NOT NULL` 컬럼 추가. |
| 수정 | `auth/config/JwtProperties.kt` | `refreshTokenTtlSeconds` 단일 키 → `rememberMeRefreshTtlSeconds`(기본 30d) + `sessionRefreshTtlSeconds`(기본 8h)로 분리. `refreshTtlSecondsFor(rememberMe)` 헬퍼 추가. **Breaking**(설정 키 이름). |
| 수정 | `auth/infrastructure/security/AuthCookieFactory.kt` | `refreshTokenCookie(value, ttl, persistent)` — `persistent=false`면 maxAge 미설정(=세션 쿠키, 브라우저 종료 시 삭제). |
| 수정 | `auth/application/command/SocialLoginCommand.kt` | `rememberMe: Boolean` 추가. |
| 수정 | `auth/application/usecase/SocialLoginUseCase.kt` | `command.rememberMe` 기반 TTL 결정, `RefreshToken.issue`에 rememberMe 전파. `Result.rememberMe` 노출. |
| 수정 | `auth/application/usecase/RefreshAccessTokenUseCase.kt` | rotation 시 `stored.rememberMe`로 새 토큰 TTL 산정 + 같은 정책 승계. `Result.rememberMe`로 컨트롤러가 쿠키 정책 결정 가능. |
| 수정 | `auth/application/usecase/GetMyProfileUseCase.kt` | `Result.bio` 노출. |
| 수정 | `auth/presentation/web/dto/request/OAuthCallbackRequest.kt` | `rememberMe: Boolean = false` 추가. |
| 수정 | `auth/presentation/web/dto/response/MyProfileResponse.kt` | `bio` 추가. |
| 수정 | `auth/presentation/web/OAuthController.kt` | callback 응답 시 `result.rememberMe`로 쿠키 발급(persistent vs session). |
| 수정 | `auth/presentation/web/TokenController.kt` | `/refresh`에서도 `result.rememberMe`로 쿠키 정책 유지. |
| 수정 | `auth/presentation/web/AuthExceptionHandler.kt` | `NoSuchElementException → 404` 매핑 추가. |
| 수정 | `auth/presentation/web/UserController.kt` | `PATCH /me` 추가, 공통 `currentUserId(jwt)` 헬퍼로 분리. |
| 수정 | `src/main/resources/application.yml` | `iam.jwt.refresh-token-ttl-seconds` 제거, `remember-me-refresh-ttl-seconds` / `session-refresh-ttl-seconds` 추가. |
| 추가 | `auth/application/command/UpdateMyProfileCommand.kt` | patch-style 입력. `clearAvatar`, `clearBio` 플래그로 명시적 삭제 표현. |
| 추가 | `auth/application/usecase/UpdateMyProfileUseCase.kt` | `@Transactional`. null=미변경, clear=null로 설정. 도메인의 `change*` 메서드를 통해 검증·`updatedAt` 갱신. |
| 추가 | `auth/presentation/web/dto/request/UpdateMyProfileRequest.kt` | `displayName/avatarUrl/bio` + `clearAvatar/clearBio`. `@Size` 검증. |

### 결정 사항
- **displayName 변경 허용**: `SocialIdentity.profileUrl`로 GitHub 원본 추적이 가능하므로, 사용자가 우리 서비스의 표시 이름을 자유롭게 바꿔도 신원 증명이 유지됨. 단 현재는 길이 검증만 적용(욕설/충돌 정책 등은 필요 시 추가).
- **avatarUrl 입력 검증**: `http(s)://` prefix 강제. SSRF/이미지 호스트 화이트리스트 등 더 엄격한 정책은 향후 도입 가능.
- **bio 정규화**: 도메인 진입 시 `trim()` + 빈 문자열 → `null`. 길이 280자(트위터 스타일).
- **patch-style 부분 업데이트**: null=변경 안 함. avatarUrl/bio를 비울 때만 `clearAvatar=true`/`clearBio=true` 명시(JSON에서 null과 "필드 누락"을 구분 못 하는 경우 대응).
- **rememberMe 정책**:
  - `true` → refresh TTL 30일 + maxAge가 명시된 **영속 쿠키** → 브라우저 재시작 후에도 자동 로그인.
  - `false` → refresh TTL 8시간 + maxAge 없는 **세션 쿠키** → 브라우저 닫으면 삭제 = 자동 로그인 비활성화.
- **브라우저별 격리**: 매 로그인이 새 family(UUID) 생성. rememberMe 정책도 family에 묶이는 게 아니라 토큰 row에 보존 → 회전 시 정책 변경 없이 유지. 디바이스마다 정책 다르게 가능.
- **명시적 로그아웃 = 자동 로그인 OFF**: `LogoutUseCase`가 family revoke + 쿠키 만료 → 다음 `/refresh` 401 → 자동 로그인 시도 실패. 별도 플래그 불필요.
- **rememberMe 정책 미노출**: TokenResponse에는 일부러 `rememberMe`를 포함하지 않음. 클라이언트 UX는 체크박스 상태로 직접 관리하는 게 자연스럽고, 서버 정책이 응답 본문으로 누설되지 않도록.

### 마이그레이션
- 기존 운영 DB가 있다면:
  - `iam_user`: `bio VARCHAR(280) NULL` 컬럼 추가.
  - `iam_refresh_token`: `remember_me BOOLEAN NOT NULL DEFAULT false` 컬럼 추가 후 default 제거(기존 row는 false로 보수적 마이그레이션).
- 설정 키 이름 변경: `iam.jwt.refresh-token-ttl-seconds` 사용처가 있으면 위의 두 키로 분리.
- 현 dev 환경(H2, `ddl-auto=update`)은 자동 적용.

### 검증
- `./gradlew clean test` 통과 (Spring Context 부팅 + 기본 테스트).

### 관련 문서
- `docs/auth-implementation.md` — User/RefreshToken 도메인, JwtProperties, AuthCookieFactory, OAuthController/TokenController, UserController(PATCH) 섹션 갱신 예정.
- `docs/auth-frontend-integration.md` — 로그인 본문에 `rememberMe`, 쿠키 동작(persistent vs session), 마이페이지 `PATCH /me` 사용 예 추가 예정.

---

## 2026-04-29 15:23:00 KST · iam-api · feat: GitHub avatar URL 수집 + 프로필 조회 API

**요청 한 줄**: GitHub OAuth 시 avatar URL도 가져와 우리 서비스 프로필에 표시할 수 있도록.

**한 줄 요약**: provider profile에 `avatarUrl` 필드를 추가하고, `User` 도메인에 보존, `GET /api/v1/users/me`로 노출.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 수정 | `auth/domain/model/User.kt` | `avatarUrl: String?` 필드 + `changeAvatar()` 추가. `create`/`rehydrate` 시그니처 확장. URL 길이(<=1024) 검증. |
| 수정 | `auth/infrastructure/jpa/UserEntity.kt` | `avatar_url VARCHAR(1024)` 컬럼 추가. `toDomain`/`fromDomain` 매핑 갱신. |
| 수정 | `auth/application/port/SocialProfileFetcherPort.kt` | `SocialProfile.avatarUrl: String?` 필드 추가. |
| 수정 | `auth/infrastructure/oauth/GithubProfileFetcher.kt` | GitHub `/user` 응답의 `avatar_url`을 `GithubUser`로 매핑하고 `SocialProfile`에 전달. |
| 수정 | `auth/application/usecase/SocialLoginUseCase.kt` | 신규 가입 시 avatar 저장. 기존 사용자는 **avatar가 null인 경우에만** provider 값으로 보강(사용자 커스터마이징 보호). |
| 추가 | `auth/application/usecase/GetMyProfileUseCase.kt` | userId로 본인 프로필 조회. `@Transactional(readOnly=true)`. |
| 추가 | `auth/presentation/web/UserController.kt` | `GET /api/v1/users/me`. JWT `sub`에서 userId 추출. |
| 추가 | `auth/presentation/web/dto/response/MyProfileResponse.kt` | `{userId, email, displayName, avatarUrl, status}` 응답 DTO. |

### 결정 사항
- **avatar는 `User`에 두고 `SocialIdentity`에는 두지 않음.**
  - 이유: 한 명이 여러 provider를 연동해도 우리 서비스에서 보여주는 avatar는 **하나의 정규(canonical) 값**이어야 자연스러움.
  - provider별 원본 URL이 필요해지면 추후 `SocialIdentity`에 별도 칼럼 추가 가능.
- **재로그인 시 동기화 정책**: avatar가 null인 케이스만 채우고, 이미 값이 있으면 덮어쓰지 않음.
  - 이유: 사용자가 직접 변경한 avatar(추후 기능)를 GitHub 값으로 덮어버리는 것을 방지.
  - 강제 동기화 기능이 필요해지면 별도 엔드포인트로 분리.
- **새 엔드포인트는 `permitAll`에 넣지 않음** → SecurityConfig 기본 정책에 의해 자동으로 JWT 인증 필수.

### 마이그레이션
- 기존 운영 DB가 있다면 `iam_user`에 `avatar_url VARCHAR(1024) NULL` 컬럼 추가 필요.
- 현 dev 환경(H2, `ddl-auto=update`)은 자동으로 컬럼이 생성됨.

### 검증
- `./gradlew clean test` 통과 (Spring Context 부팅 + 기존 `IamApiApplicationTests.contextLoads()`).

### 관련 문서
- `docs/auth-implementation.md` — User 모델, SocialLoginUseCase, UserController 섹션 갱신.
- `docs/auth-frontend-integration.md` — `/me` 엔드포인트, avatar 표시 가이드(§7.1) 추가.

---

## 2026-04-28 21:18:00 KST · iam-api · feat: GitHub OAuth 소셜 로그인 + JWT 인증 초기 구축

**요청 한 줄**: iam-api에 GitHub OAuth 소셜 로그인을 헥사고날 아키텍처로 구현. 추후 Google/iOS Apple Sign-In 확장 고려. JWT(access+refresh) 사용, 보안 엄격하게.

**한 줄 요약**: provider별 전략(strategy) 패턴, refresh token rotation + reuse detection, HttpOnly Secure 쿠키, Spring Security oauth2-resource-server 기반 stateless 인증.

### 추가 파일 (50+)
구조는 core-api의 헥사고날 컨벤션을 그대로 따름.

#### 도메인 (`auth/domain/`)
- `model/User.kt`, `model/SocialIdentity.kt`, `model/RefreshToken.kt`
- `model/vo/Email.kt`, `model/vo/SocialProvider.kt`(enum: GITHUB/GOOGLE/APPLE), `model/vo/UserStatus.kt`

#### 애플리케이션 (`auth/application/`)
- 포트(7개): `UserRepositoryPort`, `SocialIdentityRepositoryPort`, `RefreshTokenRepositoryPort`, `SocialProfileFetcherPort`, `AuthorizationUrlBuilderPort`, `JwtIssuerPort`, `SecureRandomPort`
  - `SocialProfileFetcherPort.ProviderCredential`은 `sealed interface` (AuthorizationCode | IdToken)로 web/mobile 양쪽 흐름 표현.
- 커맨드: `StartOAuthFlowCommand`, `SocialLoginCommand`, `RefreshAccessTokenCommand`
- 유스케이스(4개): `StartOAuthFlowUseCase`, `SocialLoginUseCase`, `RefreshAccessTokenUseCase`, `LogoutUseCase`

#### 프레젠테이션 (`auth/presentation/web/`)
- 컨트롤러: `OAuthController`(authorize/callback), `TokenController`(refresh/logout), `AuthExceptionHandler`
- DTO: `StartOAuthRequest`, `OAuthCallbackRequest`, `AuthorizeUrlResponse`, `TokenResponse`

#### 인프라 (`auth/infrastructure/`)
- JPA: `UserEntity`/`SocialIdentityEntity`/`RefreshTokenEntity` + JpaRepository + Adapter (총 9개)
  - `(provider, provider_user_id)` UNIQUE, `token_hash` UNIQUE, family/user 인덱스
- OAuth: `RestClientConfig`(5초 타임아웃), `GithubAuthorizationUrlBuilder`, `GithubProfileFetcher`(token 교환 + `/user` + `/user/emails` private 케이스 처리)
- JWT: `NimbusJwtIssuerAdapter`(HS256, iss/aud/sub/iat/nbf/exp/jti/typ=access), `JwtDecoderConfig`(iss/aud/typ 추가 검증)
- Random: `SecureRandomAdapter`(URL-safe Base64, no padding)
- Security: `SecurityConfig`(stateless, CSRF disable, HSTS/Frame DENY/Referrer no-referrer 등 보안 헤더), `AuthCookieFactory`

#### 설정 (`auth/config/`)
- `JwtProperties`(secret @Size(min=64) 검증, ttl), `OAuthProperties`(GitHub clientId/secret), `AuthCookieProperties`(name/domain/secure/sameSite/path), `AuthConfigRegistration`

#### 공용 (`shared/util/`)
- `HashUtils`(SHA-256)

### 수정 파일
- `build.gradle.kts` — `spring-boot-starter-web`, `validation`, `oauth2-resource-server` 추가. `oauth2-client` 제거(수동 OAuth 구현). H2 `runtimeOnly` 추가.
- `src/main/resources/application.properties` → `application.yml`로 교체. JWT/OAuth/Cookie 설정 키 정의. dev placeholder 포함, 운영 시 env 주입.

### 핵심 설계 결정
- **Provider 전략 패턴**: `List<SocialProfileFetcherPort>` / `List<AuthorizationUrlBuilderPort>`를 Spring이 자동 주입. `supports(provider)`로 라우팅. Google/Apple 추가 시 어댑터만 구현하면 됨.
- **Refresh Token Rotation**: 매 `/refresh`마다 새 토큰 발급, 이전 토큰 즉시 revoke + `replacedByHash` 기록.
- **Reuse Detection**: revoke된 토큰이 다시 들어오면 **family 전체 폐기** → 강제 재로그인. 도난 시간 한정.
- **Family**: 한 로그인 세션의 모든 회전 토큰을 묶는 `UUID`. 디바이스별 격리 + 일괄 폐기 단위.
- **Refresh 평문 보존 안 함**: DB엔 SHA-256 해시만. 평문은 발급 직후 응답 1회만 외부로.
- **HttpOnly+Secure+SameSite=Lax 쿠키**: refresh + oauth state 모두 동일.
- **OAuth State 검증**: `MessageDigest.isEqual` 상수시간 비교, path 제한(`/api/v1/auth/oauth`).
- **JWT 시크릿 검증**: `@Size(min=64)` — 부팅 시점에 약한 키 차단.
- **JwtDecoder 추가 검증**: 표준 검증에 더해 `iss`/`aud`/`typ=access` 클레임 일치 확인.

### 엔드포인트
| Method | Path | 인증 |
|---|---|---|
| POST | `/api/v1/auth/oauth/{provider}/authorize` | 불필요 |
| POST | `/api/v1/auth/oauth/{provider}/callback`  | 불필요 |
| POST | `/api/v1/auth/refresh`                    | 쿠키 |
| POST | `/api/v1/auth/logout`                     | 선택(JWT) |

### 검증
- `./gradlew clean test` 통과 (Spring Context 부팅 OK).

### 관련 문서
- `docs/auth-implementation.md` — 전체 클래스/엔드포인트 레퍼런스.
- `docs/auth-frontend-integration.md` — FE 연동 시 흐름·인터셉터·CORS·쿠키 전략.

---

## 2026-04-28 · core-api · docs: CLAUDE.md 초기 작성

**요청 한 줄**: `/init` — 저장소를 분석해 향후 Claude Code 인스턴스가 빠르게 적응할 수 있는 가이드 문서 작성.

### 추가 파일
- `core-api/CLAUDE.md`
  - 빌드/실행/테스트(단일 클래스 실행 포함) 명령
  - 헥사고날 아키텍처 디렉터리 레이아웃
  - 키 디자인 결정사항(`@Repository`를 비-JPA 포트에도 사용, `Fingerprint`의 SHA-256 + 라인넘버 정규화, `@attach(markerId)` 임베드 토큰, `ErrorCase` private 생성자 + 팩토리, 유스케이스 `Result` 노출 방식)
  - 스택 버전(Kotlin 2.3.20, JVM 25, Spring Boot 4.0.4, Gradle 9.4)

---

## 작성 가이드 (TL;DR)

새 항목 추가 시 아래 템플릿 사용:

```markdown
## YYYY-MM-DD HH:MM:SS KST · <service> · <type>: <한 줄 제목>

**요청 한 줄**: 사용자가 어떻게 부탁했는지 압축.

**한 줄 요약**: 무엇을 했는지.

### 변경 파일
| 분류 | 경로 | 내용 |
|---|---|---|
| 추가 | ... | ... |
| 수정 | ... | ... |

### 결정 사항
- ...

### 마이그레이션 (있는 경우)
- ...

### 검증
- ...

### 관련 문서
- ...
```

- `<service>` 컨벤션: `iam-api` / `core-api` / `insight-api` / `noti-api` / `publish-api` / `repo`(루트 빌드/문서) / `multi`(여러 서비스 동시 변경).
- `<type>` 컨벤션: `feat` / `fix` / `refactor` / `docs` / `chore` / `breaking`.

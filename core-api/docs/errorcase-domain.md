# 에러케이스 · 스텝 · 솔루션 — 도메인 정책과 구현 설계

> 최종 갱신: 2026-05-28
> 관련 변경: 루트 `docs/changelog.md` 의 "Step / Solution 도메인 — 해결 시도 타임라인 + 해결 방법 묶음" 항목.
> 이 문서는 **에러케이스 본 도메인**(케이스 → 시도 → 해결방법 묶음) 의 모델·상태 전이·권한·본문 구조 결정을 한 곳에 정리한다. 첨부/스니펫 라이프사이클은 [`attachment-lifecycle.md`](attachment-lifecycle.md) 와 [`revisitable-decisions.md`](revisitable-decisions.md) 참조.

---

## 0. TL;DR — 한눈에 보기

```
       ┌──────────────────────  ErrorCase  ───────────────────────────┐
       │  title · scope · paste(→snapshot) · description · meta       │
       │  ownerUserId · workspaceId? · status(자동/사용자 전이)         │
       │                                                              │
       │   ┌─ snippets  ─┐    ┌─ attachments ─┐                       │
       │   │  markerId   │    │  markerId      │                       │
       │   │  ↕ N (link) │    │  ↕ N (link)   │                       │
       │   └─────────────┘    └────────────────┘                       │
       │                                                              │
       │   ┌────────  steps  (타임라인) ─────────────┐                   │
       │   │ orderIndex 0,1,2,…                       │                  │
       │   │ status: SUCCESS / PARTIAL / FAILURE      │                  │
       │   │ title · insight(요약) · attemptType       │                  │
       │   │ body: 자유 마크다운 (@snippet/@attach 임베드)             │
       │   └──────────┬────────────────────────────────┘                 │
       │              │ stepIds (순서·중복불가)                            │
       │   ┌──────────▼──────  solutions  (해결방법 묶음) ──────────┐    │
       │   │  title (한 문장) · stepIds: List<Long>(순서 보존)        │   │
       │   │  한 케이스에 N 개 가능 (여러 해결방법 발견 가능)          │   │
       │   └─────────────────────────────────────────────────────────┘   │
       └──────────────────────────────────────────────────────────────┘
```

핵심 정책:
- **본문은 자유, 메타는 구조화** — step body 는 마크다운 자유, `title`/`status`/`insight`/`attemptType` 만 구조화(검색·필터·요약). 옵트인 템플릿 헤더(`## 시도 / ## 결과 / ## 학습`)는 프런트가 끼워주는 UI 보조이고 백엔드는 자유 본문 그대로 저장.
- **상태 전이는 도메인이 검증** — `ErrorCase.transitionTo()` 가 허용된 전이만 통과시킨다. 첫 step 추가 시 `OPEN → IN_PROGRESS` 자동, `RESOLVED` 는 사용자 명시.
- **타임라인 + 묶음** — step 들은 시도 타임라인, solution 은 그 중 일부를 골라 "최종 해결방법" 으로 묶은 템플릿.
- **권한은 케이스 기준** — step/solution 의 읽기·쓰기는 모두 부모 케이스의 권한(`ErrorCaseAccess`)을 그대로 따른다. 워크스페이스 케이스면 멤버 역할(READ/WRITE/ADMIN) 에 위임.

---

## 1. 도메인 모델

### 1.1 ErrorCase (애그리거트 루트)
| 필드 | 타입 | 비고 |
|------|------|------|
| `id` | `Long` | PK |
| `ownerUserId` | `Long` | 생성자(불변) |
| `title` | `String` ≤200 | |
| `scope` | `String?` | 자유 문자열(현재) — [`revisitable-decisions`](revisitable-decisions.md) 항목 1 C 참고 |
| `snapshot` | `ErrorSnapshot?` | `paste` 로부터 추출(클래스/메시지/스택/지문) |
| `description` | `String?` | 마크다운. `@snippet(markerId)`/`@attach(markerId)` 인라인 임베드 |
| `meta` | `Meta` | `workspaceId?` / `severity?(1..4)` / `environment?` |
| `visibility` | `Visibility` | **var**. `PUBLIC`(기본) / `PRIVATE` / `WORKSPACE`. WORKSPACE 는 workspaceId 필수. PUBLIC 승격은 워크스페이스 ADMIN 만(워크스페이스 케이스의 경우) |
| `snippets` / `attachments` | List | markerId 기준 N:1 연결(`errorCaseId` FK) |
| `status` | `ErrorCaseStatus` | **var**. 도메인 `transitionTo()` 로만 변경 |
| `occurredAt` | `LocalDateTime?` | 사용자 입력 |
| `createdAt` / `updatedAt` | | |

### 1.2 Step (타임라인 한 칸)
| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `id` / `errorCaseId` / `authorUserId` | | | |
| `orderIndex` | `Int` | ✓ | 0-based, 등록 시 `count` 로 자동 부여 — `orderIndex ASC, id ASC` 로 정렬 |
| `title` | `String` ≤200 | ✓ | 타임라인 카드 헤드라인 |
| `status` | `SUCCESS` / `PARTIAL` / `FAILURE` | ✓ | 색상·아이콘 + RESOLVED 추천 트리거 |
| `attemptType` | enum (옵션) | | `CODE_CHANGE`/`CONFIG`/`DEPENDENCY`/`ENV`/`ROLLBACK`/`INVESTIGATION`/`OTHER` |
| `body` | `TEXT?` | | 자유 마크다운. `@snippet`/`@attach` 임베드. 비어도 OK |
| `insight` | `String?` ≤500 | 권장 | 한두 문장 요약. SUCCESS=학습, FAILURE=원인. **카드에 항상 표시** |

### 1.3 Solution (해결방법 묶음)
| 필드 | 타입 | 비고 |
|------|------|------|
| `id` / `errorCaseId` / `authorUserId` | | |
| `title` | `String` ≤200 | 한 문장 요약 |
| `stepIds` | `MutableList<Long>` | 같은 케이스의 step만. 중복 금지. 순서 유의미. `@OrderColumn` |

테이블 구조:
```
error_case_solution        (id, error_case_id, author_user_id, title, ...)
error_case_solution_step   (solution_id, step_id, order_index)   ← join + 순서
```

---

## 2. ErrorCase 상태 모델과 전이

```
            ┌──── transitionTo() (도메인 검증) ────┐
            │                                       │
   [DRAFT] ─┴──▶ [OPEN] ──첫 step──▶ [IN_PROGRESS] ─┴──▶ [RESOLVED]
                       │ (자동)        │                      │
                       │              │ ◀── 재오픈 ───────────┘
                       │              │
                       └──────────────┴──▶ [CLOSED]   (운영자 / 향후)
```

| 현재 | 허용된 다음 |
|------|------------|
| DRAFT | OPEN, CLOSED |
| OPEN | IN_PROGRESS, CLOSED |
| IN_PROGRESS | RESOLVED, OPEN, CLOSED |
| RESOLVED | IN_PROGRESS, CLOSED |
| CLOSED | (없음, terminal) |

전이 책임:
- **`OPEN → IN_PROGRESS`** — 자동(`CreateStepUseCase` 가 첫 step 추가 시).
- **`IN_PROGRESS → RESOLVED`** — *사용자 확인*. SUCCESS step + IN_PROGRESS 면 `CreateStepUseCase` 가 응답에 `suggestResolve=true` hint → SPA 모달 → 사용자가 동의하면 `PATCH /error-cases/{id} { status: "RESOLVED" }` 호출.
- **나머지 전이** — `PATCH /error-cases/{id} { status: ... }` 로 사용자 명시. 도메인이 위 표에 어긋나면 IllegalArgumentException → 컨트롤러가 400 매핑.
- 같은 상태 전이는 멱등 no-op.

---

## 3. Step 본문 구조 — D방식 + 옵트인 템플릿

### 3.1 설계 의도
사용자가 짚은 트레이드오프:
- 구조화 ↑ → 일관성·검색·요약은 좋지만 **빈 칸 압박** → 사용자가 안 쓴다.
- 구조화 ↓ → 가볍지만 사용자마다 형식 제각각 → "한눈에 보기" 가치 손실.

채택한 절충(D + 옵트인 C):
- **메타 4종은 항상 구조화 컬럼**(`title`/`status`/`insight`/`attemptType`) — 타임라인 표시·필터·집계의 기반.
- **본문(`body`) 은 자유 마크다운**. 비워도 됨. 코드/이미지/외부 링크 자유.
- **옵트인 템플릿 헤더 삽입 버튼**(`## 시도 / ## 결과 / ## 학습` 등) — **프런트 UI 책임**. 클릭하면 본문 마크다운에 헤더 텍스트만 끼워넣고 사용자가 채우거나 지움. 백엔드는 자유 본문 그대로 저장(템플릿 식별 안 함).

### 3.2 비교
| 옵션 | 채택 안 함 이유 |
|---|---|
| A. 완전 자유 한 칸 | 타임라인에서 "한눈에" 어려움(메타 부재) |
| B. 강한 구조(필수 5칸) | 빈 칸 압박 → 사용자가 회피 |
| C. 옵트인 템플릿 만 | 메타도 옵트인이라 검색/요약 불가 |
| **D + 약한 C (채택)** | 메타는 강제, 본문은 자유, 템플릿은 보조 |
| E. AI 추출 | 비용·범위 밖 |

### 3.3 `insight` 의 위치
사용자가 명시 요청한 "한두 문장 요약 섹션". **자유 본문 안의 한 줄이 아니라 별도 컬럼**으로 둔 이유:
- 타임라인 카드 렌더링에서 본문을 다 읽지 않아도 핵심이 한 줄로 보여야 함.
- 검색·집계·노출 컴포넌트가 단일 출처(`insight` 컬럼)에서 가져와야 일관됨.
- ≤500 자 제약으로 "한두 문장" 의도 강제.

---

## 4. 자동 상태 전이와 RESOLVED 추천 흐름

```
사용자                백엔드(CreateStepUseCase)              ErrorCase
  │                       │                                       │
  │  POST .../steps       │                                       │
  ├──────────────────────▶│                                       │
  │                       │  findById, requireWrite               │
  │                       │  count = stepRepo.countByCase         │
  │                       │  step = Step.create(orderIndex=count) │
  │                       │  stepRepo.save                        │
  │                       │  if (status==OPEN && count==0)        │
  │                       │      errorCase.transitionTo(IN_PROGRESS)
  │                       │      errorCaseRepo.update             │
  │                       │  suggestResolve =                     │
  │                       │      step.status==SUCCESS &&          │
  │                       │      case.status==IN_PROGRESS         │
  │  201 { step, caseStatus, suggestResolve }                     │
  │◀──────────────────────┤                                       │
  │ (suggestResolve=true 면 모달)                                  │
  │                                                                │
  │  PATCH /error-cases/{id} { status: "RESOLVED" }                │
  ├───────────────────────────────────────────────────────────────▶│
  │                       (UpdateErrorCaseUseCase → transitionTo)  │
```

핵심:
- **백엔드는 `RESOLVED` 로 자동 전이하지 않는다.** 사용자 의도가 필요한 결정이고, "해결됐다" 의 판단은 사람의 몫.
- `suggestResolve` 는 *권유* 다 — `false` 라도 사용자가 직접 PATCH 로 RESOLVED 전환 가능.
- 같은 트랜잭션에서 step 저장 + 케이스 status 변경 → 일관성.

---

## 5. Solution 정책

- **묶음 의미**: solution = "이 케이스를 만났을 때 최종 적용할 해결 절차". step 의 부분집합 + 순서.
- **N개 허용**: 한 케이스에 여러 해결방법이 있을 수 있음(환경·조건별). solution 끼리는 독립.
- **stepIds 검증** (`CreateSolutionUseCase`):
  - 비어있으면 400 (`SolutionInvalidException`).
  - 중복 stepId 400.
  - 타 케이스의 step 포함 시 400.
- **순서 보존**: `@OrderColumn` 으로 사용자 선택 순서 그대로 (`stepIds[0]`, `stepIds[1]`, …).
- **수정**: 본 구현에선 title 수정만 (`Solution.updateTitle()`). stepIds 재구성은 후속(현재 미구현 — 삭제 후 재생성).
- **삭제 권한**: solution 작성자 또는 **케이스 소유자**(둘 다 인정). 둘 다 아닌 워크스페이스 ADMIN 은 현재 거부 — 후속 확장 포인트.
- **참조 보호**: solution 이 참조하는 step 삭제 시 → **409**, 사용자가 solution 먼저 정리해야 함. 자동 stepIds 정리는 의도적으로 안 함(돌발 삭제로 solution 의미가 사일런트하게 깨지는 걸 막음).

---

## 6. 권한 모델 (`ErrorCaseAccess`) — 2026-06-01 Visibility 분기, 2026-06-02 role hierarchy 명시화

step/solution/comment/케이스의 모든 권한 판정은 단일 헬퍼로. **`WorkspaceRole` enum 이 hierarchy 메서드 보유** (ADMIN > WRITE > READ) — `meetsOrExceeds(required)` · `canRead()` · `canWriteContent()` · `canAdminister()`:

```kotlin
fun requireRead(case, user) =
    case.ownerUserId == user ||
    case.visibility == PUBLIC ||
    (case.visibility == WORKSPACE
       && case.workspaceId != null
       && workspaceQuery.getViewerRole(user, ws)?.canRead() == true)        // ← hierarchy

fun requireWrite(case, user) =
    case.ownerUserId == user ||
    (case.visibility == WORKSPACE
       && case.workspaceId != null
       && workspaceQuery.getViewerRole(user, ws)?.canWriteContent() == true) // WRITE 이상

/** 워크스페이스 케이스를 PUBLIC 으로 승격 — 워크스페이스 ADMIN 만. */
fun requirePublicPromotion(case, user) =
    workspaceQuery.getViewerRole(user, ws)?.canAdminister() == true          // ADMIN
```

> **WorkspaceRole hierarchy**: 선언 순서(`READ`=0, `WRITE`=1, `ADMIN`=2) ordinal 로 자연 표현. `meetsOrExceeds(required)` 한 줄로 일관 비교. 새 role 추가 시 컴파일러가 enum 한 곳만 보면 되도록 enum 본인이 hierarchy 책임을 가짐 (`role == ADMIN` 같은 enum 직접 비교는 사용하지 않는다 — `canAdminister()` 쓰기). iam-api 는 사용자에게 *단일 role* 만 부여하고 상위가 하위를 *자동 포함*.

| 작업 | 필요 권한 |
|---|---|
| Step 목록·상세 / Solution 목록 / **댓글 조회·작성·리액션·도움됨** | 케이스 READ — Visibility 분기 |
| Step 추가 / Solution 추가·삭제 | 케이스 WRITE(소유자 또는 워크스페이스 WRITE+, PUBLIC 이라도 owner 만) |
| Step 수정·삭제 / 댓글 수정·삭제 | 작성자 본인만(케이스 권한과 별개) |
| 케이스 status 전이(PATCH) / visibility 변경 | 케이스 소유자만(`UpdateErrorCaseUseCase`) |
| **워크스페이스 케이스 → PUBLIC 승격** | **워크스페이스 ADMIN 만** (`ErrorCaseAccess.requirePublicPromotion`) |
| Diff 제안 상태 변경(반영/거부) | 케이스 owner 만 (Visibility 무관) |

**왜 step 수정은 author 만?** — 협업 워크스페이스에서 누가 적은 시도 기록을 다른 멤버가 함부로 고치면 진실성 손상. 잘못된 step 은 새 step 으로 *반박/보완* 하는 게 타임라인 의미와 일치.

---

## 7. Cascade 삭제 순서 (`DeleteErrorCaseUseCase`)

```
사용자 → DELETE /error-cases/{id}
                 │
                 ▼
   ┌────────────────────────────────────┐
   │ 자식 먼저 → 케이스 마지막            │
   │                                    │
   │ 1) attachments (file + row, 멱등)   │
   │ 2) snippets   (row, 멱등)           │
   │ 3) solutions  (bulk DELETE)         │
   │ 4) steps      (bulk DELETE)         │
   │ 5) error_case (row)                 │
   └────────────────────────────────────┘
```

설계 포인트:
- **solution → step 순서** — step 삭제는 solution 참조 보호 정책 때문에 409 가 날 수 있다. cascade 경로에선 *위에서 아래로* 삭제하므로 solution 을 먼저 일괄 삭제해야 step 정리가 충돌 없음.
- **클래스 레벨 `@Transactional` 없음** — 첨부 삭제는 파일 I/O(비트랜잭셔널). 큰 트랜잭션으로 묶으면 파일은 지웠는데 row 는 살아있는 "찢어진 상태" 위험. 각 자식은 멱등 단일 루틴(`AttachmentDeleter`/`SnippetDeleter`) — 중간 실패 시 재시도가 자기치유. 자세한 근거는 [`attachment-lifecycle.md`](attachment-lifecycle.md).
- step/solution 은 파일이 없어 단순 bulk DELETE 로 충분.

---

## 8. 함정·운영 노트

### 8.1 `ErrorCase.status` 를 `var` 로 변경
원래 `val` 이었던 status 를 step 자동 전이 도입을 위해 `var` + `transitionTo()` 로 변경. **재구성(reconstitute) 호출 측은 영향 없음**(named arg). 단 `@field:` 어노테이션이나 reflection 기반 직렬화에 의존하는 코드가 있다면 검토 필요.

### 8.2 잘못된 전이 → 400
도메인 `transitionTo()` 의 `require(next in allowed)` 가 위반되면 `IllegalArgumentException`. `ErrorCaseController.update` 에 `catch (e: IAE) → 400` 매핑 추가. (core-api 는 현재 전역 `@RestControllerAdvice` 가 없으므로 컨트롤러 catch 필수 — [`revisitable-decisions`](revisitable-decisions.md) 의 "에러 응답 구조" 분석 참고.)

### 8.3 `orderIndex` 의 재정렬
현재 등록 순서 고정. 드래그&드롭 재정렬 API 는 미구현. 추후 추가 시 동시성 안전을 위해 컬럼을 한꺼번에 갱신하거나 (workspace_id, order_index) 유니크 제약은 두지 않는다(임시 swap 어려움).

### 8.4 dev `ddl-auto: update` → 운영 Flyway
- 자동 생성 테이블: `error_case_step`, `error_case_solution`, `error_case_solution_step`(join).
- 운영은 Flyway 마이그레이션 SQL 로 명시(컬럼 길이/인덱스/CHECK constraint). 특히 enum 컬럼의 CHECK 가 `ddl-auto=update` 에선 자동 수정되지 않음(회원탈퇴 작업 시 직접 겪은 함정).

### 8.5 Step body 의 임베드 토큰
`@snippet(markerId)` / `@attach(markerId)` 는 **케이스에 이미 연결된 marker 만** 의미를 가짐(렌더 시 케이스의 snippets/attachments 에서 찾아냄). step 자체 connector 모델은 두지 않았다 — step 별 별도 연결이 필요해지면 `case PATCH` 의 선언형 재연결 패턴을 step 에 대칭 적용 가능(현재는 의도적으로 단순화).

### 8.6 `scope` 미검증
`scope` 는 여전히 검증 없는 자유 문자열. `revisitable-decisions` 항목 1 C 에 트래킹 중. 결정 갈래(enum 도입 vs workspaceId 로 유도) 가 정해지면 본 도메인의 status/scope 검증도 함께 정리.

---

## 9. 엔드포인트 요약

```
ErrorCase
  POST   /api/v1/error-cases                 — 생성
  GET    /api/v1/error-cases                 — 목록(커서 무한스크롤 + 필터)
  GET    /api/v1/error-cases/{id}            — 상세(snippets/attachments 포함)
  PATCH  /api/v1/error-cases/{id}            — 본문/메타/상태 부분 수정 + 선언형 재연결
  DELETE /api/v1/error-cases/{id}            — cascade

Step
  POST   /api/v1/error-cases/{caseId}/steps                — 추가 (자동 IN_PROGRESS, suggestResolve hint)
  GET    /api/v1/error-cases/{caseId}/steps                — 타임라인 (orderIndex ASC)
  PATCH  /api/v1/error-cases/{caseId}/steps/{stepId}       — 작성자만, null=유지
  DELETE /api/v1/error-cases/{caseId}/steps/{stepId}       — 참조 solution 있으면 409

Solution
  POST   /api/v1/error-cases/{caseId}/solutions            — stepIds 검증, 순서 보존
  GET    /api/v1/error-cases/{caseId}/solutions            — createdAt ASC
  DELETE /api/v1/error-cases/{caseId}/solutions/{solutionId} — 작성자 또는 케이스 소유자
```

응답·실패 코드의 자세한 매핑은 Swagger UI(`/swagger-ui.html`) 참조.

---

## 10. 확장 포인트

- **Step 재정렬 API** — 드래그&드롭 UX. `PATCH /error-cases/{caseId}/steps/order { stepIds: [...] }`.
- **Solution title 수정** — 현재 미구현. `PATCH /solutions/{id} { title }`.
- **Solution stepIds 재구성** — 현재는 삭제·재생성. `PATCH /solutions/{id} { stepIds: [...] }` 로 선언형 재구성 가능(케이스 PATCH 의 선언형 재연결과 같은 패턴).
- **워크스페이스 ADMIN 의 step/solution 강제 수정** — 모더레이션 정책. 현재 미지원.
- **Step body 의 step 전용 임베드 모델** — 케이스와 다른 marker 가 필요해지면 step 자체에 `snippetMarkerIds`/`attachmentMarkerIds` 도입(케이스 PATCH 의 선언형 재연결 미러링).
- **Step 별 댓글/스레드** — 협업 워크스페이스에서 다른 멤버가 step 에 의견 추가. 권한 모델 별도 설계.
- **케이스 status 의 enum 확장** — DRAFT 임시 저장 UX 정착 시 컨트롤러/유스케이스에서 DRAFT 처리 추가([`ErrorCaseStatus.kt`](../src/main/kotlin/org/studieojavry/coreapi/errorcase/domain/model/vo/ErrorCaseStatus.kt) TODO).
- **Solution 의 템플릿화 / 재사용 / 매칭** — 같은 fingerprint 의 다른 케이스 solution 노출(Phase 1) → "적용" 버튼으로 복제(Phase 2) → `SolutionTemplate` 정식 카탈로그(Phase 3) → 시맨틱 매칭(Phase 4). 설계 결정의 지형도는 [`revisitable-decisions.md`](revisitable-decisions.md) §4 참조.
- **댓글(Comment) 시스템 ✅ 구현 완료(2026-05-31, 2026-06-01 갱신)** — `errorcase/comment/` sub-module 신설. 인용 3종(NONE/CASE_BODY/STEP — CODE_BLOCK 흡수), 드래그→인용 자동 판정 UX, depth=1 답글(서버측 redirect), Slack 스타일 이모지 리액션, **모든 사용자 도움됨 토글 + userIds**, soft delete + placeholder, @멘션 적재, **GitHub Suggest 차용 Diff 제안**(코드블럭 gutter 드래그). 제품 정책·UX·통신 흐름은 [`comment-discussion.md`](comment-discussion.md), **코드 구현 가이드**는 [`comment-implementation.md`](comment-implementation.md), 설계 결정 회고는 [`revisitable-decisions.md`](revisitable-decisions.md) §7·§8.

---

## 11. 관련 코드

도메인:
- `domain/model/ErrorCase.kt` — `status: var`, `transitionTo()`, `update()`.
- `domain/model/Step.kt`, `Solution.kt`.
- `domain/model/vo/ErrorCaseStatus.kt` (`DRAFT`/`OPEN`/`IN_PROGRESS`/`RESOLVED`/`CLOSED`).
- `domain/model/vo/StepStatus.kt` (`SUCCESS`/`PARTIAL`/`FAILURE`).
- `domain/model/vo/AttemptType.kt`.

응용:
- `application/usecase/ErrorCaseAccess.kt` — 공통 읽기/쓰기 권한 헬퍼.
- `application/usecase/CreateStepUseCase.kt` — 자동 IN_PROGRESS 전환 + suggestResolve hint.
- `application/usecase/ListStepsUseCase.kt`, `UpdateStepUseCase.kt`, `DeleteStepUseCase.kt`.
- `application/usecase/CreateSolutionUseCase.kt` — stepIds 검증.
- `application/usecase/ListSolutionsUseCase.kt`, `DeleteSolutionUseCase.kt`.
- `application/usecase/UpdateErrorCaseUseCase.kt` — `status` 전이 호출 + 선언형 재연결.
- `application/usecase/DeleteErrorCaseUseCase.kt` — cascade(자식 먼저 → 케이스).
- 포트: `StepRepositoryPort`, `SolutionRepositoryPort`.

인프라:
- `infrastructure/jpa/entity/StepEntity.kt`, `SolutionEntity.kt`(`@OrderColumn` join table).
- `infrastructure/jpa/StepJpaRepository.kt`, `SolutionJpaRepository.kt` + 어댑터.

표현:
- `presentation/web/StepController.kt`, `SolutionController.kt`.
- `presentation/web/ErrorCaseController.kt` — `PATCH` 에 `status` + IAE→400 매핑.
- request/response DTO: `dto/request/StepRequests.kt`, `dto/response/StepResponses.kt`, `dto/request/UpdateErrorCaseRequest.kt`(status 필드).

참고:
- 루트 `docs/changelog.md` — "Step / Solution 도메인" 항목.
- 같은 폴더 `attachment-lifecycle.md` — 첨부/스니펫 라이프사이클 + 케이스 cascade 근거.
- 같은 폴더 `revisitable-decisions.md` — scope 미검증, 에러응답 advice 미도입 등 재검토 결정.

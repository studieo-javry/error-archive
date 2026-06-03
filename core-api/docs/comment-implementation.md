# 댓글(Comment) 시스템 — 구현 가이드

> 최종 갱신: 2026-06-01
> 관련 변경: 루트 `docs/changelog.md` 의 "댓글 시스템 구현 / CODE_BLOCK 흡수 / Diff 제안 추가" 항목
> 이 문서는 **코드가 어떻게 짜였는지** 를 한곳에 본다 — 레이어별 클래스/포트/엔티티 카탈로그, 호출 그래프, DB 매핑, 함정. *제품 정책·UX·통신 흐름* 종합은 [`comment-discussion.md`](comment-discussion.md), *결정 회고* 는 [`revisitable-decisions.md`](revisitable-decisions.md) §7·§8.

---

## 0. TL;DR — 한눈에

```
errorcase/comment/                       ← errorcase BC 의 7번째 sub-aggregate
├─ domain/                               ← 프레임워크 의존 0
│   ├─ model/
│   │   ├─ Comment                       (애그리거트 루트 — body, depth=1, quote, soft delete)
│   │   ├─ CommentReaction               (Slack 스타일 이모지 — UNIQUE(c,u,emoji))
│   │   ├─ CommentHelpful                (모든 사용자 토글 — UNIQUE(c,u))
│   │   ├─ CommentMention                (@사용자 토큰 적재 — Phase 3 noti)
│   │   └─ CommentSuggestion             (★ GitHub Suggest 차용 — UNIQUE(c))
│   └─ model/vo/
│       ├─ QuoteSourceKind               (NONE/CASE_BODY/STEP)
│       ├─ SuggestionSourceType          (SNIPPET/MARKDOWN_CODE)
│       └─ SuggestionStatus              (PENDING/APPLIED/REJECTED)
│
├─ application/
│   ├─ command/                          ← 입력 캐리어 (data class)
│   │   └─ CommentCommands               (Create/Update/UpdateSuggestionStatus)
│   ├─ port/
│   │   └─ CommentRepositoryPort         ← 5 도메인 통합 단일 포트
│   └─ usecase/                          ← @Service, @Transactional
│       ├─ CreateCommentUseCase          (인용 검증·depth=1·멘션·suggestion 저장)
│       ├─ ListCommentsUseCase           (트리·cursor·sort·filter·quoteSummary)
│       ├─ UpdateCommentUseCase          (작성자 only, body, editedAt)
│       ├─ DeleteCommentUseCase          (soft + 부수 cascade)
│       ├─ ToggleHelpfulUseCase
│       ├─ ReactionUseCases              (Add/Remove + ReactionAggregate)
│       └─ UpdateSuggestionStatusUseCase (★ 케이스 owner only)
│
├─ infrastructure/jpa/
│   ├─ entity/                           ← @Entity (5)
│   │   ├─ CommentEntity / ReactionEntity / HelpfulEntity / MentionEntity / SuggestionEntity
│   ├─ Comment*JpaRepository (5)         ← Spring Data interface, @Modifying bulk
│   └─ adapter/CommentRepositoryAdapter  ← 포트 단일 구현, 5 JPA repo 주입
│
└─ presentation/web/
    ├─ CommentController                 (8 endpoints + Swagger Tag/Operation)
    └─ dto/
        ├─ request/CommentRequests       (Create/Update/Reaction/UpdateSuggestionStatus)
        └─ response/CommentResponses     (CommentResponse/List/Helpful/Reaction/Suggestion + mappers)
```

**호출 흐름** (Hexagonal):
```
HTTP → Controller → Command(data) → UseCase(@Service) → Port(interface) → Adapter(@Component) → JpaRepository
                                              ↓ 권한
                                  shared/ErrorCaseAccess.requireRead
```

총 30 파일 · 5 테이블 · 8 endpoints. `CreateCommentUseCase` 가 1회 호출에 *Comment + Mention + (옵션)Suggestion* 3개 도메인을 동시 저장하는 단일 진입점.

---

## 1. 디렉토리 구조 (실제 파일)

```
src/main/kotlin/org/studieojavry/coreapi/errorcase/comment/
├── domain/
│   ├── model/
│   │   ├── Comment.kt
│   │   ├── CommentHelpful.kt
│   │   ├── CommentMention.kt
│   │   ├── CommentReaction.kt
│   │   └── CommentSuggestion.kt
│   └── model/vo/
│       ├── QuoteSourceKind.kt
│       ├── SuggestionSourceType.kt
│       └── SuggestionStatus.kt
├── application/
│   ├── command/CommentCommands.kt
│   ├── port/CommentRepositoryPort.kt
│   └── usecase/
│       ├── CreateCommentUseCase.kt              (+ CommentInvalidException)
│       ├── DeleteCommentUseCase.kt
│       ├── ListCommentsUseCase.kt
│       ├── ReactionUseCases.kt                  (Add/Remove + ReactionAggregate)
│       ├── ToggleHelpfulUseCase.kt
│       ├── UpdateCommentUseCase.kt              (+ CommentNotFoundException, CommentAccessDeniedException)
│       └── UpdateSuggestionStatusUseCase.kt
├── infrastructure/jpa/
│   ├── CommentHelpfulJpaRepository.kt
│   ├── CommentJpaRepository.kt
│   ├── CommentMentionJpaRepository.kt
│   ├── CommentReactionJpaRepository.kt
│   ├── CommentSuggestionJpaRepository.kt
│   ├── adapter/CommentRepositoryAdapter.kt
│   └── entity/
│       ├── CommentEntity.kt
│       ├── CommentHelpfulEntity.kt
│       ├── CommentMentionEntity.kt
│       ├── CommentReactionEntity.kt
│       └── CommentSuggestionEntity.kt
└── presentation/web/
    ├── CommentController.kt
    └── dto/
        ├── request/CommentRequests.kt
        └── response/CommentResponses.kt
```

---

## 2. 도메인 레이어 — 5 모델 + 3 VO

> **프레임워크 의존 0** (JPA·Spring annotation 없음). private constructor + companion `create()`/`rehydrate()` 패턴.

### 2.1 애그리거트 루트: `Comment`

| 항목 | 값 |
|---|---|
| 책임 | 본문(body), depth=1 답글, 인용 메타, 편집·삭제 lifecycle |
| 주요 필드 | `id, errorCaseId, authorUserId, parentCommentId?, body, quoteSourceKind, quoteSourceId?, quoteSnapshot?, quoteSnapshotTruncated, editedAt?, deletedAt?, createdAt, updatedAt` |
| 상수 | `BODY_MAX=5000`, `QUOTE_SNAPSHOT_MAX=500`, `DELETED_BODY_PLACEHOLDER="(삭제된 본문)"` |
| 핵심 메서드 | `edit(newBody, at)` · `softDelete(at)` · `isDeleted` · `isTopLevel` |
| companion | `create(...)` (snapshot truncate 포함) · `rehydrate(...)` · `truncateSnapshot(s)` |
| invariants (init) | `body` 비공백 / NONE 면 sourceId·snapshot 없음 / body ≤ 5000 |

### 2.2 부수 도메인 4개

| 클래스 | 책임 | 핵심 제약 | companion |
|---|---|---|---|
| `CommentReaction` | Slack 이모지 리액션 | `EMOJI_MAX=16`, 비공백 | `create / rehydrate` |
| `CommentHelpful` | "도움됨" 마크 (모든 사용자 토글) | 없음 — DB UNIQUE(c,u) 가 멱등 보장 | `create / rehydrate` |
| `CommentMention` | @사용자 식별자 적재 | `IDENTIFIER_MAX=100` (자동 trim+take) | `create / rehydrate` |
| `CommentSuggestion` | ★ Diff 제안 — comment 1:1 | `CODE_MAX=10_000`, `startLine ≥ 1`, `endLine ≥ startLine`, `sourceId` 비공백 | `create(status=PENDING) / rehydrate / changeStatus(new, at)` + `additions`/`deletions` derived |

### 2.3 VO (enum 3종)

| Enum | 값 | 비고 |
|---|---|---|
| `QuoteSourceKind` | `NONE / CASE_BODY / STEP` | 본문 안 코드/스니펫은 *본문의 일부* — CODE_BLOCK 흡수 (`revisitable-decisions.md` §7) |
| `SuggestionSourceType` | `SNIPPET / MARKDOWN_CODE` | sourceId 는 *opaque String* (BE 검증 X) |
| `SuggestionStatus` | `PENDING / APPLIED / REJECTED` | 작성 시 PENDING. 변경은 케이스 owner 만 |

---

## 3. 응용 레이어 — 1 포트 + 8 유스케이스

### 3.1 `CommentRepositoryPort` — 단일 통합 포트

```
@Repository interface CommentRepositoryPort {
    // Comment
    save(c) / findById(id) / findAllByErrorCaseId(caseId) / deleteAllByErrorCaseId(caseId)
    // Reactions
    findReactionsByCommentIds(ids) / saveReaction(r) / deleteReaction(c,u,e) / deleteAllReactionsByCommentId(c)
    // Helpful
    findHelpfulByCommentIds(ids) / saveHelpful(h) / deleteHelpful(c,u) / deleteAllHelpfulByCommentId(c)
    // Mentions
    saveMentions(list) / deleteAllMentionsByCommentId(c) / findMentionsByCommentIds(ids)
    // Suggestions (★)
    saveSuggestion(s) / findSuggestionByCommentId(c) / findSuggestionsByCommentIds(ids) / deleteSuggestionByCommentId(c)
}
```

> 다섯 도메인을 **하나의 포트** 로 묶었다. (`ErrorCaseRepositoryPort` 가 snapshot/snippets 까지 묶는 패턴과 일관.) 호출자 입장에서 의존성 1개, batch fetch 가 일관.

### 3.2 유스케이스 카탈로그 (8개)

모두 `@Service @Transactional`. 모든 mutation 진입 시 `ErrorCaseAccess.requireRead(case, userId)` (suggestion 상태 변경만 owner 검사).

| 클래스 | 입력 | 책임 | 의존 |
|---|---|---|---|
| **`CreateCommentUseCase`** | `CreateCommentCommand` | (1) `requireRead` (2) **depth=1 redirect** (`resolveTopLevelParent`) (3) **인용 검증** (`validateQuoteSource` — STEP 만 검사) (4) `Comment.create` + save (5) `@MENTION_PATTERN` 파싱 → `saveMentions` (최대 `MENTIONS_MAX=20`) (6) **suggestion 첨부 시 `CommentSuggestion.create` + `saveSuggestion`** | `ErrorCaseRepository`, `StepRepository`, `CommentRepository`, `ErrorCaseAccess` |
| **`ListCommentsUseCase`** | `Input(caseId, requesterUserId, sort, quoteKindFilter, cursor, size)` | (1) `requireRead` (2) `findAllByErrorCaseId` 전체 fetch (3) top-level 필터+sort (4) cursor 적용 (`<createdAt iso>|<id>` Base64URL, HELPFUL 미지원) (5) page IDs + 답글 IDs 모아 **reaction/helpful/mention/suggestion 4 batch IN 쿼리** (6) 트리 그루핑 + `quoteSummary{general, caseBody, steps[]}` 집계 | `ErrorCaseRepository`, `CommentRepository`, `ErrorCaseAccess` |
| `UpdateCommentUseCase` | `UpdateCommentCommand(commentId, requesterUserId, body)` | 작성자 본인 검사 → `c.edit(body)` (editedAt 자동) → save. 삭제된 댓글은 400 | `CommentRepository` |
| **`DeleteCommentUseCase`** | `(commentId, requesterUserId)` | 작성자 본인 검사 → **bulk @Modifying 정리 4개 먼저**(reaction/helpful/mention/suggestion) → `c.softDelete()` → save **마지막**. 멱등 (이미 삭제면 no-op) | `CommentRepository` |
| `ToggleHelpfulUseCase` | `(commentId, requesterUserId)` | `requireRead` + 토글(있으면 delete / 없으면 saveHelpful) → `Result(helpedByMe, count, userIds)` | `ErrorCaseRepository`, `CommentRepository`, `ErrorCaseAccess` |
| `AddReactionUseCase` | `(commentId, requesterUserId, emoji)` | `requireRead` + 멱등 add → `ReactionAggregate(emoji, count, userIds, reactedByMe)` | 위와 동일 |
| `RemoveReactionUseCase` | 동일 | 본인 row 삭제 + 집계 응답 | 위와 동일 |
| **`UpdateSuggestionStatusUseCase`** | `UpdateSuggestionStatusCommand(commentId, requesterUserId, newStatus)` | (1) comment 조회 (2) errorCase 조회 → **`errorCase.ownerUserId == requesterUserId` 검사** (3) suggestion 조회 → 없으면 400 (4) `suggestion.changeStatus(newStatus)` + save | `ErrorCaseRepository`, `CommentRepository` |

### 3.3 예외 클래스

| 예외 | HTTP 매핑 | throw 위치 |
|---|---|---|
| `CommentNotFoundException(id)` | 404 | UseCase 전반 |
| `CommentAccessDeniedException(msg)` | 403 | Update/Delete/UpdateSuggestionStatus |
| `CommentInvalidException(msg)` | 400 | Create(인용·depth)/Update(deleted)/Reaction(deleted)/Helpful(deleted)/UpdateSuggestionStatus(no suggestion) |

> `CommentNotFoundException`·`CommentAccessDeniedException` 정의 위치: `UpdateCommentUseCase.kt` (파일 끝). `CommentInvalidException`: `CreateCommentUseCase.kt`.

### 3.4 Command 객체

```
CreateCommentCommand(
  errorCaseId, authorUserId, parentCommentId?,
  body, quoteSourceKind, quoteSourceId?, quoteSnapshot?,
  suggestion: SuggestionInput?      ← ★ Diff 제안 동시 첨부
)
SuggestionInput(sourceType, sourceId, startLine, endLine, oldCode, newCode)

UpdateCommentCommand(commentId, requesterUserId, body)
UpdateSuggestionStatusCommand(commentId, requesterUserId, newStatus: SuggestionStatus)
```

---

## 4. 인프라 레이어 — JPA

### 4.1 엔티티 (5)

각 엔티티는 `toDomain()` 인스턴스 메서드 + companion `fromDomain(d)` 정적 메서드.

| Entity | Table | 핵심 컬럼·제약·인덱스 |
|---|---|---|
| `CommentEntity` | `error_case_comment` | `body TEXT NOT NULL`, `quote_source_kind VARCHAR(16) NOT NULL`, indexes: `ix_comment_case_created(error_case_id, created_at)` · `ix_comment_parent(parent_comment_id)` · `ix_comment_author(author_user_id)` |
| `CommentReactionEntity` | `error_case_comment_reaction` | `UNIQUE(comment_id, user_id, emoji)` |
| `CommentHelpfulEntity` | `error_case_comment_helpful` | `UNIQUE(comment_id, user_id)` |
| `CommentMentionEntity` | `error_case_comment_mention` | index `(comment_id)` |
| `CommentSuggestionEntity` ★ | `error_case_comment_suggestion` | `UNIQUE(comment_id)`, `old_code/new_code TEXT NOT NULL`, indexes: `ix_comment_suggestion_source(source_type, source_id)` · `ix_comment_suggestion_status(status)` |

### 4.2 JPA Repository (5)

Spring Data `JpaRepository<E, Long>` 인터페이스. 핵심 쿼리는 `findAllByCommentIdIn` (batch) + `@Modifying(clearAutomatically=true)` bulk delete.

| Repository | 주요 메서드 |
|---|---|
| `CommentJpaRepository` | `findAllByErrorCaseIdOrderByCreatedAtAscIdAsc(id)` · `@Modifying deleteAllByErrorCaseId(id)` |
| `CommentReactionJpaRepository` | `findAllByCommentIdIn(ids)` · `@Modifying deleteByCommentUserEmoji` · `@Modifying deleteByCommentId` |
| `CommentHelpfulJpaRepository` | `findAllByCommentIdIn(ids)` · `@Modifying deleteByCommentUser` · `@Modifying deleteByCommentId` |
| `CommentMentionJpaRepository` | `findAllByCommentIdIn(ids)` · `@Modifying deleteByCommentId` |
| `CommentSuggestionJpaRepository` ★ | `findByCommentId(id)` · `findAllByCommentIdIn(ids)` · `@Modifying deleteByCommentId` · `deleteByCommentIdIn(ids)` |

### 4.3 `CommentRepositoryAdapter` — 포트 단일 구현

```
@Component class CommentRepositoryAdapter(
    commentJpa, reactionJpa, helpfulJpa, mentionJpa, suggestionJpa,
) : CommentRepositoryPort
```

- `save(Comment)` 는 **기존 엔티티 fetch + 변경 가능 필드만 갱신** 패턴 (불변 필드 보호). suggestion 도 동일 패턴 (status/updatedAt 만 갱신).
- `findAllByErrorCaseId` 결과는 `ASC, ASC` — 정렬·필터는 응용 레이어가 메모리에서 처리.

---

## 5. 표현 레이어 — Controller + DTO

### 5.1 `CommentController` — 8 endpoints

```
@RestController @RequestMapping("/api/v1/error-cases/{caseId}/comments")
@Tag(name="error-case-comments") class CommentController(8 deps)
```

| Method | Path | UseCase | HTTP 코드 | 권한 |
|---|---|---|---|---|
| `POST` | `` | `CreateCommentUseCase` (suggestion 옵션) | 201 | read |
| `GET` | `` | `ListCommentsUseCase` | 200 | read |
| `PATCH` | `/{commentId}` | `UpdateCommentUseCase` | 200 | 작성자 |
| `DELETE` | `/{commentId}` | `DeleteCommentUseCase` | 204 | 작성자 |
| `POST` | `/{commentId}/helpful` | `ToggleHelpfulUseCase` | 200 | read |
| `POST` | `/{commentId}/reactions` | `AddReactionUseCase` | 200 | read |
| `DELETE` | `/{commentId}/reactions/{emoji}` | `RemoveReactionUseCase` | 200 | read |
| `PATCH` | `/{commentId}/suggestion/status` ★ | `UpdateSuggestionStatusUseCase` | 200 | **케이스 owner** |

모든 메서드:
- `@AuthenticationPrincipal userId: Long` 으로 인증 사용자 추출 (`@Parameter(hidden=true)`)
- 응답 빌드 시 `errorCaseRepository.findById(caseId)?.ownerUserId` 로 `isAuthorOfCase` 계산
- `try/catch` 로 도메인 예외 → `ResponseStatusException` 매핑
- `@Operation`/`@ApiResponses`/`@Parameter`/`@Schema` 동시 작성 (Swagger 정책)

### 5.2 Request DTO (`CommentRequests.kt`)

- `CreateCommentRequest` — body(NotBlank, ≤5000) · quoteSourceKind(default NONE) · quoteSourceId? · quoteSnapshot? · **`suggestion: SuggestionRequest?`** (`@field:Valid`)
  - `SuggestionRequest` — sourceType · sourceId(NotBlank, ≤128) · startLine(@Min 1) · endLine(@Min 1) · oldCode(≤10_000) · newCode(≤10_000)
- `UpdateCommentRequest` — body(NotBlank, ≤5000)
- `AddReactionRequest` — emoji(NotBlank, ≤16)
- `UpdateSuggestionStatusRequest` — status: SuggestionStatus ★

### 5.3 Response DTO (`CommentResponses.kt`)

```
CommentResponse(
  id, errorCaseId, authorUserId, isAuthorOfCase,
  parentCommentId?, body,
  quote: QuoteDto?,            // null 이면 NONE
  reactions: List<ReactionDto>,
  helpful: HelpfulDto,
  mentions: List<String>,
  suggestion: SuggestionDto?,  // ★
  editedAt?, deletedAt?, createdAt, updatedAt,
  replies: List<CommentResponse>   // depth=1
)

CommentListResponse(items, nextCursor, hasNext, quoteSummary: { general, caseBody, steps[] })

SuggestionDto(id, sourceType, sourceId, startLine, endLine, oldCode, newCode,
              status, additions, deletions, lines: List<DiffLineDto>, createdAt, updatedAt)
DiffLineDto(type: "del"|"add", oldNo?, newNo?, code)
```

### 5.4 매퍼 (`CommentResponses.kt` 끝)

| Mapper | 위치 | 역할 |
|---|---|---|
| `Comment.toQuoteDto()` | extension | NONE 이면 null 반환 |
| `ReactionAggregate.toDto()` | extension | usecase output → response DTO |
| `CommentSuggestion.toDto()` ★ | extension | **단순 line-단위 unified diff lines 생성** — `oldCode` 전체를 `del`, `newCode` 전체를 `add` 로. HTML mockup 의 `buildDiff` 와 동일 패턴 |
| `ListCommentsUseCase.CommentNode.toResponse(caseOwnerUserId)` | extension | 트리 재귀 매핑, `isAuthorOfCase` 계산, suggestion?.toDto() 적용 |

---

## 6. 데이터 흐름 (3 핵심 시나리오)

### 6.1 댓글 작성 (suggestion 포함)

```
HTTP POST /comments {body, quoteSourceKind, ..., suggestion?}
  ↓
CommentController.create
  ↓ CreateCommentCommand 생성 (suggestion → SuggestionInput)
CreateCommentUseCase.invoke           [@Transactional]
  ├─ errorCaseRepo.findById(caseId)
  ├─ access.requireRead(case, userId)            // shared
  ├─ resolveTopLevelParent(parentId)             // depth=1 강제 (top-level walk-up)
  ├─ validateQuoteSource(STEP→stepRepo.findById)
  ├─ Comment.create(...) + truncateSnapshot
  ├─ commentRepo.save(c)
  ├─ MENTION_PATTERN.findAll(body) → CommentMention.create × N → commentRepo.saveMentions
  └─ suggestion? → CommentSuggestion.create(PENDING) → commentRepo.saveSuggestion
  ↓
controller : commentRepo.findSuggestionByCommentId 재조회 → toDto()
  ↓
201 CommentResponse(suggestion: { lines: [del...add...] })
```

### 6.2 댓글 목록 (트리 + N+1 회피)

```
HTTP GET /comments?sort=&quoteKind=&cursor=&size=
  ↓
CommentController.list
  ↓ enum 파싱 (Sort, QuoteKindFilter)
ListCommentsUseCase.invoke            [@Transactional(readOnly)]
  ├─ requireRead
  ├─ commentRepo.findAllByErrorCaseId(caseId)     // ASC, ASC — 전체 fetch
  ├─ top-level filter + sort(NEWEST/OLDEST/HELPFUL)
  ├─ applyCursor("createdAt|id" Base64URL)
  ├─ page IDs + 답글 IDs 모음 = allIds
  ├─ 4 batch IN 쿼리:
  │    reactions = findReactionsByCommentIds(allIds)
  │    helpful   = findHelpfulByCommentIds(allIds)
  │    mentions  = findMentionsByCommentIds(allIds)
  │    suggestions = findSuggestionsByCommentIds(allIds)  ★
  ├─ buildNode(top) + replies(child).map { buildNode } (createdAt ASC)
  └─ quoteSummary 집계
  ↓
items.map { it.toResponse(caseOwnerUserId) }       // suggestion?.toDto() 안에서 lines 생성
  ↓
200 CommentListResponse(items, nextCursor, hasNext, quoteSummary)
```

### 6.3 Diff 제안 상태 변경 (케이스 owner 만)

```
HTTP PATCH /comments/{id}/suggestion/status {status}
  ↓
UpdateSuggestionStatusUseCase.invoke   [@Transactional]
  ├─ commentRepo.findById → 없으면 404
  ├─ errorCaseRepo.findById(c.errorCaseId) → 없으면 404
  ├─ errorCase.ownerUserId == requesterUserId ? 아니면 CommentAccessDeniedException (403)
  ├─ commentRepo.findSuggestionByCommentId → 없으면 CommentInvalidException (400)
  ├─ suggestion.changeStatus(newStatus)
  └─ commentRepo.saveSuggestion(s)                // adapter 가 status/updatedAt 만 갱신
  ↓
200 SuggestionStatusResponse(commentId, status, updatedAt)
```

---

## 7. DB 테이블 매핑 요약

```
┌──────────────────────────────────────────────────────────────────────┐
│ error_case (외부 — case sub-aggregate)                                │
│   id, owner_user_id, ...                                              │
└────────────────┬─────────────────────────────────────────────────────┘
                 │ FK by id (도메인 객체에선 Long, JPA 관계 X — sub-module 경계)
                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│ error_case_comment                                                    │
│   id, error_case_id, author_user_id, parent_comment_id?,              │
│   body TEXT, quote_source_kind VARCHAR(16), quote_source_id?,         │
│   quote_snapshot VARCHAR(500)?, quote_snapshot_truncated,             │
│   edited_at?, deleted_at?, created_at, updated_at                     │
│   IX (error_case_id, created_at), IX (parent_comment_id), IX (author) │
└────┬────────────┬───────────────┬───────────────┬───────────────────┘
     │            │               │               │
     │ N:M        │ N:M           │ N:M           │ 1:1
     ▼            ▼               ▼               ▼
┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────────────────────────┐
│ reaction │ │ helpful  │ │ mention  │ │ suggestion                 │
│ UNIQUE   │ │ UNIQUE   │ │ IX(c)    │ │ UNIQUE(comment_id)         │
│ (c,u,e)  │ │ (c,u)    │ │          │ │ IX(source_type, source_id) │
│          │ │          │ │          │ │ IX(status)                 │
└──────────┘ └──────────┘ └──────────┘ └────────────────────────────┘
```

> 모든 테이블은 `ddl-auto=update` 로 hibernate 가 자동 생성 (로컬). 운영은 Flyway 명시 마이그레이션 — `quote_source_kind` CHECK 제약은 `comment-discussion.md` §8.2 참조.

---

## 8. 호출 그래프 (전체)

```
┌──────────────────────────── presentation ─────────────────────────────┐
│ CommentController (8 endpoints)                                       │
│   ├─ Create  ─┐                                                       │
│   ├─ List    ─┤                                                       │
│   ├─ Update  ─┤                                                       │
│   ├─ Delete  ─┤                                                       │
│   ├─ Helpful ─┤  (try/catch → ResponseStatusException 매핑)           │
│   ├─ ReactAdd─┤                                                       │
│   ├─ ReactDel─┤                                                       │
│   └─ Suggest ─┘                                                       │
└──────┬────────────────────────────────────────────────────────────────┘
       │ Command(data class)
       ▼
┌──────────────────────────── application ──────────────────────────────┐
│ CreateCommentUseCase  ───┐                                            │
│ ListCommentsUseCase   ───┤                                            │
│ UpdateCommentUseCase  ───┤                                            │
│ DeleteCommentUseCase  ───┤  → CommentRepositoryPort (interface)       │
│ ToggleHelpfulUseCase  ───┤  → ErrorCaseRepositoryPort (외부)           │
│ Add/RemoveReaction    ───┤  → StepRepositoryPort (외부, Create 만)     │
│ UpdateSuggestionStatus───┘  → shared/ErrorCaseAccess.requireRead       │
└──────┬────────────────────────────────────────────────────────────────┘
       │ Comment/Reaction/Helpful/Mention/Suggestion 도메인
       ▼
┌──────────────────────────── infrastructure ───────────────────────────┐
│ CommentRepositoryAdapter (@Component)                                 │
│   ├─ CommentJpaRepository                                             │
│   ├─ CommentReactionJpaRepository                                     │
│   ├─ CommentHelpfulJpaRepository                                      │
│   ├─ CommentMentionJpaRepository                                      │
│   └─ CommentSuggestionJpaRepository      ★                            │
│        └─ Entity (toDomain/fromDomain)                                │
└───────────────────────────────────────────────────────────────────────┘
```

---

## 9. 함정·운영 노트

### 9.1 ★ bulk @Modifying + clearAutomatically=true 순서 함정 (잡았음)

`DeleteCommentUseCase` 에서:
```
✗ 잘못된 순서: c.softDelete() + save  → bulk @Modifying 정리
                                          ↑ clearAutomatically 가 persistence context 비움 → softDelete UPDATE 누락
✓ 옳은 순서:   bulk @Modifying 정리 4개(reaction/helpful/mention/suggestion)
              → c.softDelete() + save (마지막)
```
회원탈퇴 finalize 시점에 한 번 잡았던 동일 패턴 — 두 번째 적용. **bulk 정리는 항상 도메인 save 보다 앞으로**.

### 9.2 N+1 회피 — page IDs + 답글 IDs 한꺼번에

`ListCommentsUseCase.invoke` 에서 `allIds = pageIds + childIds` 를 만들고, reactions/helpful/mentions/**suggestions** 4개 모두 `findAllBy...In(allIds)` 단일 IN 쿼리. 100개 댓글 페이지 = 4 쿼리 (+ 본문 1).

### 9.3 권한 매트릭스 (한 줄)

| 경로 | 권한 |
|---|---|
| 댓글 조회·작성·답글·리액션·도움됨 | `requireRead` — **Visibility 분기**: PUBLIC=로그인 누구나 / WORKSPACE=그 워크스페이스 멤버 / PRIVATE=owner |
| 댓글 수정·삭제 | 작성자 본인 (`c.authorUserId == requesterUserId`) |
| **Diff 제안 상태 변경** ★ | **케이스 owner** (`errorCase.ownerUserId == requesterUserId`) — Visibility 무관 |

### 9.4 sourceId 는 opaque

`CommentSuggestion.sourceId` 는 **BE 검증 X**. SNIPPET → snippet.markerId 또는 FE 부여 식별자, MARKDOWN_CODE → 예: `step-2-code-1`. dangling 시 FE 가 "원본을 찾을 수 없습니다" 토스트로 처리 — quote stale 패턴과 일관. (snippet→comment 의존 끊기 위한 의도적 약결합.)

### 9.5 diff lines 는 *단순* — 진짜 LCS 가 아님

`CommentSuggestion.toDto()` 는 `oldCode` 전체를 `del`, `newCode` 전체를 `add` 로 출력. ctx(변경 없음) 행 없음. HTML mockup 의 `buildDiff` 와 동일. 진짜 LCS 는 Phase 2 (java-diff-utils 등).

### 9.6 케이스 삭제 cascade

`DeleteErrorCaseUseCase` 가 댓글 cascade 책임 — 본 sub-module 안에서는 `commentRepo.deleteAllByErrorCaseId` 만 노출. (그 use case 가 호출 측에서 reactions/helpful/mentions/suggestions 도 cascade.)

### 9.7 Mention 식별자

현재 `mentionedIdentifier` 는 *@뒤 문자열 그대로* (예: "이성훈"). iam-api 의 사용자 식별자(displayName/username) 매핑은 알림 발송 시점에 별도. 매핑 실패 시에도 댓글 본문은 그대로 보존.

---

## 10. 외부 의존 (sub-module 경계)

| 의존 대상 | 어디서 | 이유 |
|---|---|---|
| `case/ErrorCaseRepositoryPort` | Create/List/Reaction/Helpful/UpdateSuggestionStatus | 케이스 존재·owner 확인 |
| `step/StepRepositoryPort` | Create | STEP 인용 시 step.errorCaseId 검증 |
| `shared/ErrorCaseAccess.requireRead` | 거의 모든 read-거치는 UseCase | 권한 단일 진입점 — **Visibility(PUBLIC/PRIVATE/WORKSPACE) 분기** 포함 |
| `case/ErrorCaseAccessDeniedException` · `ErrorCaseNotFoundException` | UseCase / Controller | 403/404 매핑 |

> **comment → snippet 의존 없음** (의도적). `revisitable-decisions.md` §7 의 CODE_BLOCK 흡수 결정 이후 `CodeSnippetRepositoryPort.findById(Long)` 도 롤백 제거. Diff 제안의 sourceId 도 opaque 로 받아 의존 안 들임.

---

## 11. 관련 문서

| 주제 | 위치 |
|---|---|
| **제품 정책·UX·통신 흐름** 종합 | [`comment-discussion.md`](comment-discussion.md) |
| Diff 제안 정책·시나리오 | [`comment-discussion.md`](comment-discussion.md) §11 |
| 결정 회고 (대안·근거) | [`revisitable-decisions.md`](revisitable-decisions.md) §7 |
| 댓글 BC 위치 결정 | [`revisitable-decisions.md`](revisitable-decisions.md) §8 |
| 도메인 전체 맥락 | [`errorcase-domain.md`](errorcase-domain.md) |
| 같은 sub-module 패턴(첨부) | [`attachment-lifecycle.md`](attachment-lifecycle.md) |
| 변경 이력 | 루트 [`docs/changelog.md`](../../docs/changelog.md) |

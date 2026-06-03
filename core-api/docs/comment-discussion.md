# 댓글(Discussion) 시스템 — 모델·정책·FE/BE 역할·통신 흐름

> 최종 갱신: 2026-06-01 (Diff 제안 댓글 추가 — GitHub Suggest 차용)
> 관련 변경: 루트 `docs/changelog.md` 의 "댓글 시스템 구현 (errorcase/comment 신설)" 항목.
> 이 문서는 케이스 하단 토론 영역의 **데이터 모델·확정 정책·FE/BE 역할 분담·통신 흐름·기능 인벤토리** 를 한 곳에서 본다. 설계 결정 회고/대안은 [`revisitable-decisions.md`](revisitable-decisions.md) §7, 도메인 전체 맥락은 [`errorcase-domain.md`](errorcase-domain.md) 참조.

---

## 0. TL;DR — 한눈에 보기

```
[ErrorCase 상세 페이지]
   ├─ 본문(description)               ── 드래그 → CASE_BODY 인용 댓글
   │   └ 본문에 임베드된 @snippet(...) 도 본문의 일부로 본다 → CASE_BODY
   ├─ Step 카드 N개                    ── 드래그 → STEP 인용 댓글
   └─ Solution 카드 (있을 때)
        ▼
   [Discussion 섹션 — 하단]
     필터: ALL · 일반 · 본문 · Step
     정렬: 최신순(기본) · 오래된순 · 도움됨 우선
     ┌────────────────────────────────────────────────┐
     │ 🙂 이성훈 [작성자]   2시간 전   [본문]            │
     │   "인용된 본문 한 줄…" (snapshot)   ← 클릭→원문으로 slide
     │   본문 마크다운…  ```kotlin ... ```               │
     │   👍 4 · 🎯 2   ⭐도움됨 4  ●●●+1                  │
     │   ↳ 답글들 (depth=1, 같은 줄기)                   │
     └────────────────────────────────────────────────┘
```

핵심:
- **인용 3종 — NONE / CASE_BODY / STEP** (SIGNATURE·CODE_BLOCK 미채택). 인용 없는 댓글은 뱃지 X. 본문에 임베드된 스니펫·코드도 *본문의 일부* 로 본다.
- **"원문 보기" 슬라이드** — 인용문 클릭 시 FE 가 `quoteSnapshot` 으로 본문 내 첫 매치 위치를 찾아 `scrollIntoView`. 별도 sourceId 불필요.
- **드래그 → 인용 자동 판정**(FE 가 DOM 컨텍스트로 source 결정, BE 는 그대로 받음).
- **★ GitHub 스타일 Diff 제안** — 코드블럭 **gutter(라인번호) 드래그 → 라인 범위 + after 코드** → 댓글에 첨부. 케이스 owner 가 *반영/거부* 토글. §11 참조.
- **답글은 depth=1** (서버측 강제 redirect — 답글의 답글은 같은 줄기에 평탄).
- **이모지 리액션**(Slack 스타일 UNIQUE) + **도움됨 모든 사용자 토글** + **카운트·사용자 stack 표시 + popover**.
- **수정·삭제는 본인만**, 삭제는 **soft delete + placeholder** 유지. **편집됨** 표시.
- **권한은 케이스 read 권한** 동일(`ErrorCaseAccess.requireRead`) — 토론 도구라 READ 권한자도 작성 가능. **Diff 제안 상태 변경만은 케이스 owner 한정**.
- 응답은 **트리(top-level + replies[])** + **quoteSummary**(일반/본문/Step별 카운트).

---

## 1. 데이터 모델

### 1.1 Comment (애그리거트 루트)

| 필드 | 타입 | 비고 |
|------|------|------|
| `id` | Long | PK |
| `errorCaseId` | Long | 부모 케이스 |
| `authorUserId` | Long | 작성자 |
| `parentCommentId` | Long? | depth=1 — top-level=null, 답글=top-level id |
| `body` | TEXT | 마크다운, `@사용자`/`@snippet(...)`/`@attach(...)` 토큰 허용. ≤5000자 |
| `quoteSourceKind` | enum `NONE` / `CASE_BODY` / `STEP` | 인용 종류 (3종) |
| `quoteSourceId` | Long? | STEP=step id / 그 외 null |
| `quoteSnapshot` | varchar(500)? | 인용 시점 텍스트 (자동 truncate) |
| `quoteSnapshotTruncated` | Boolean | 500자 잘림 여부 |
| `editedAt` | timestamp? | 수정 시 set → "(편집됨)" |
| `deletedAt` | timestamp? | soft delete 시 set |
| `createdAt` / `updatedAt` | timestamp | |

### 1.2 부수 도메인 4개

| 엔티티 | 목적 | 키 / 제약 |
|---|---|---|
| `CommentReaction` | Slack 스타일 이모지 리액션 | `UNIQUE (commentId, userId, emoji)` — 한 사용자가 같은 댓글에 같은 이모지 1회 |
| `CommentHelpful` | "도움됨" 표시 — 모든 사용자 토글 | `UNIQUE (commentId, userId)` |
| `CommentMention` | `@사용자` 멘션 — 알림 라우팅(Phase 3 noti-api) | `(commentId, mentionedIdentifier)` |
| `CommentSuggestion` | GitHub 스타일 Diff 제안 (§11) | `UNIQUE (commentId)` — 댓글 1:1 |

### 1.3 테이블

```
error_case_comment            (id, errorCaseId, authorUserId, parentCommentId?, body, quoteSourceKind,
                               quoteSourceId?, quoteSnapshot?, quoteSnapshotTruncated, editedAt?, deletedAt?,
                               createdAt, updatedAt)
   index: (errorCaseId, createdAt), (parentCommentId), (authorUserId)

error_case_comment_reaction   (id, commentId, userId, emoji, createdAt)
   UNIQUE(commentId, userId, emoji)

error_case_comment_helpful    (id, commentId, userId, createdAt)
   UNIQUE(commentId, userId)

error_case_comment_mention    (id, commentId, mentionedIdentifier)
   index: (commentId)

error_case_comment_suggestion (id, commentId, sourceType, sourceId, startLine, endLine,
                               oldCode, newCode, status, createdAt, updatedAt)
   UNIQUE(commentId)
   index: (sourceType, sourceId), (status)
```

---

## 2. 확정 정책 매트릭스

| 축 | 정책 |
|---|---|
| 표시 위치 | 케이스 상세 하단 **Discussion 섹션** |
| 인용 종류 | `NONE` (일반) · `CASE_BODY` · `STEP` — **3종 확정**. SIGNATURE·CODE_BLOCK 미채택 |
| 본문 내 코드/스니펫 | 본문에 임베드된 `@snippet(id)`·코드블록은 *본문의 일부* → `CASE_BODY` 로 통합 |
| 인용 없는 댓글 | 응답에서 `quote=null` — 프런트가 뱃지 비노출 |
| 인용 출처 표기 | `[본문]` / `[Step N]` — Step 라벨은 짧게(`Step 2`) |
| 인용 자동 판정 | **FE 드래그→DOM `.selectable` dataset** 으로 `quoteSourceKind`/`SourceId`/`Label` 자동 |
| "원문 보기" 점프 | **FE 가 `quoteSnapshot` 으로 본문/Step 텍스트에서 첫 매치 위치를 찾아 `scrollIntoView` + 글로우**. 실패 시 토스트 "본문이 수정되었을 수 있어요" |
| `quoteSnapshot` 길이 제한 | **500자** 자동 truncate(`quoteSnapshotTruncated=true`) |
| 본문 마크다운 | 코드블록(``` ```) + 인라인 코드(`` ` ``) + `>` 인용문 + `@사용자` 멘션 + `@snippet(id)`·`@attach(id)` 임베드 |
| 답글 | **depth = 1** — 서버측 자동 redirect(답글의 답글은 부모의 부모로) |
| 역할 배지 | **`[작성자]` 만**(case owner). 기여자/해결자/ADMIN 미채택 |
| 시각 표시 | 절대시간(기본) + 프런트가 상대시간 변환 가능 |
| 이모지 리액션 | Slack 스타일 — `UNIQUE(commentId, userId, emoji)`, 같은 사용자 같은 emoji 또 누르면 멱등 |
| 도움됨 | **모든 사용자 토글 가능**(케이스 read 권한자). 응답에 `count` + `userIds` + `helpedByMe` |
| 도움됨 UI | 미니 아바타 stack 3~4개 + `+N` chip + 클릭 시 popover 사용자 목록 |
| 인용 snapshot stale | 원본 수정/삭제 감지 시 ⚠️ 표시(향후 — 현재는 truncated 만) |
| 수정 | **본인만**, body 만 변경, `editedAt` 세팅 → "(편집됨 · ts)" |
| 삭제 | **본인만**, **soft delete**(body=`(삭제된 본문)`, `deletedAt` 세팅). 답글 placeholder 유지 |
| 공유 | URL fragment `#comment-{id}` — 백엔드 변경 0, 프런트가 anchor 처리 |
| @멘션 | 본문 파싱 → `CommentMention` 적재. 알림 라우팅은 Phase 3 noti-api |
| 권한 (작성/조회) | **`ErrorCaseAccess.requireRead`** — 토론 친화. WRITE+ 가 아닌 READ 멤버도 작성 가능 |
| 정렬 | `NEWEST`(기본) / `OLDEST` / `HELPFUL`. HELPFUL 은 cursor 미지원 |
| 필터 | `ALL` / `GENERAL` / `CASE_BODY` / `STEP` |
| 응답 형태 | **트리** — top-level + `replies[]`. 답글은 `createdAt ASC` (시간 흐름) |
| `quoteSummary` | 응답 metadata 에 포함 — `{general, caseBody, steps[]}`. 사이드 패널 단일 요청 렌더 |
| **Diff 제안** | 코드블럭 **gutter 드래그** → 라인 범위 + after 코드 (§11). 댓글 1:1. status PENDING/APPLIED/REJECTED. **케이스 owner 만 상태 변경**. sourceType: SNIPPET / MARKDOWN_CODE. sourceId opaque. 자동 코드 반영 X — 표시만 |

---

## 3. FE / BE 역할 분담

### 3.1 FE 책임 (브라우저)

| 영역 | 책임 |
|---|---|
| **드래그 인용 자동화** | `mouseup` → `getSelection()` → `closest('.selectable')` → dataset(`data-target-type`/`-id`/`-label`)으로 `quoteSourceKind`(CASE_BODY/STEP)/`Id`/`Label` 자동 결정. 본문 안 어디든 — 평문이든 임베드 스니펫이든 — CASE_BODY. "인용 댓글 달기" FAB 표시 |
| **인용 미리보기** | composer 위에 source 뱃지 + 잘린 텍스트 (3줄 clamp). 인용 제거 버튼 |
| **"원문 보기" slide** | 인용문 클릭 시 `quoteSnapshot` 으로 본문/Step 텍스트에서 `indexOf` 첫 매치 → `scrollIntoView({behavior:'smooth'})` + 글로우. 매치 실패 시 토스트 |
| **마크다운 렌더** | ``` ``` 코드블록, `` ` `` 인라인, `>` 인용, `@사용자` → mention chip, `@snippet(id)` / `@attach(id)` → embed token chip |
| **답글 UI 들여쓰기** | depth=1 — top-level 옆에 replies 한 단계만 들여쓰기. 답글의 "답글 달기" 도 같은 parent 로 호출 |
| **이모지 리액션 UI** | 칩 클릭 토글, "+ 추가" 로 임의 이모지 입력. 본인 reacted 면 `byMe` 강조 |
| **도움됨 UI** | 미니 아바타 stack(최대 3~4) + `+N` + 카운트. 클릭 시 popover 로 사용자 목록 + 토글 버튼 |
| **시간 표시** | 절대시간 + 상대시간 변환(`3시간 전` 등) + hover 절대 |
| **댓글 anchor** | URL fragment `#comment-{id}` — 공유 버튼 = clipboard 복사. 진입 시 fragment 자동 점프 + 강조 |
| **편집 도구** | 코드블록/인라인/`>` 인용/`@멘션`/`@snippet` 자동 삽입 버튼 |
| **인용 위치 점프** | quoteSummary 사이드 카드 클릭 → 해당 source 로 스크롤 + 글로우 |
| **삭제 placeholder** | `deletedAt!=null` 이면 `[삭제된 댓글] {author} · {deletedAt} 삭제` 회색 박스 |
| **편집됨 표시** | `editedAt!=null` 이면 `(편집됨 · {editedAt})` |
| **사용자 정보 조회** | `authorUserId` / `helpful.userIds` 등으로 iam-api 의 `/users/{id}` batch 호출(또는 캐시). 백엔드는 user 정보 임베드 X |
| **권한 분기 UI** | 본인 댓글만 수정/삭제 버튼 노출. case owner UI 차별화는 `isAuthorOfCase` 플래그 사용 |

### 3.2 BE 책임 (core-api/comment)

| 영역 | 책임 |
|---|---|
| **권한 검사** | 모든 작성/조회/리액션/도움됨에서 `ErrorCaseAccess.requireRead(case, userId)` |
| **인용 source 검증** | `STEP` → `step.errorCaseId == caseId`. `CASE_BODY` → `quoteSnapshot` 필수 (errorCaseId 자체가 source). 위반 시 400 |
| **depth=1 redirect** | `parentCommentId` 가 답글이면 그 부모의 부모(top-level)로 자동 변경 후 저장 |
| **quoteSnapshot truncate** | 500자 초과 시 자동 자름 + `quoteSnapshotTruncated=true` |
| **@멘션 파싱** | body 의 `@[A-Za-z0-9_.가-힣-]+` 추출 → `CommentMention` 적재(최대 20) |
| **이모지 리액션 멱등** | UNIQUE 제약 + 추가 전 존재 확인 → no-op |
| **도움됨 토글** | UNIQUE 제약 + 토글 로직 + 응답에 `count` / `userIds` / `helpedByMe` |
| **soft delete** | body=`(삭제된 본문)` 로 갈음 + `deletedAt` 세팅. 부수: reactions/helpful/mentions 함께 정리 |
| **트리 응답 그루핑** | top-level cursor 페이징(`createdAt|id` Base64URL) + 그 답글들을 children 으로 묶어 1 응답 |
| **quoteSummary 집계** | 응답 metadata 에 `{general, caseBody, steps[]}` 동봉 |
| **정렬/필터** | sort=NEWEST/OLDEST/HELPFUL · quoteKind=ALL/GENERAL/CASE_BODY/STEP |
| **`isAuthorOfCase` 계산** | 응답 빌드 시 `comment.authorUserId == case.ownerUserId` 비교 |
| **케이스 삭제 cascade** | `DeleteErrorCaseUseCase` 가 댓글/reactions/helpful/mentions 까지 정리 |
| **알림 라우팅 (예정)** | Phase 3 noti-api 도입 시 멘션·답글·도움됨 이벤트 발행 |

### 3.3 경계 — *백엔드가 안 하는 것*

- **사용자 표시 정보 임베드 X** — 응답에 `displayName`/`avatarUrl` 안 넣음. 프런트가 `/users/{id}` 로 별도 조회(N+1 회피 위해 batch / 캐시).
- **상대시간 변환 X** — 절대시간만, 프런트가 "3시간 전" 변환.
- **마크다운 렌더 X** — 본문은 *원문 그대로* 저장·반환. 프런트가 렌더.
- **드래그 source 결정 X** — FE 가 DOM 컨텍스트로 결정해서 보냄.

---

## 4. 통신 흐름 시나리오 (Sequence)

### 4.1 일반 댓글 작성

```
User       FE                                    BE
 │          │                                     │
 │ 본문 입력 + 제출                                  │
 │ ────────▶│ POST /comments                      │
 │          │   { body, quoteSourceKind:"NONE" } │
 │          │ ──────────────────────────────────▶│ requireRead(case, userId)
 │          │                                     │ Comment.create(parent=null, NONE)
 │          │                                     │ 멘션 파싱 → 적재
 │          │                                     │ save
 │          │ ◀──── 201 CommentResponse ──────────│ (quote=null, helpful 0, reactions [])
 │          │ 목록 갱신 (낙관적 prepend or refetch) │
```

### 4.2 ★ 드래그 → 인용 댓글 (핵심 시나리오)

```
User                FE                                                 BE
 │                   │                                                  │
 │ 본문/Step 영역 텍스트 드래그 (본문 안 임베드 스니펫 코드도 본문)            │
 │ ─────────────────▶│ document.mouseup                                  │
 │                   │  ① getSelection()                                 │
 │                   │  ② anchorNode.closest('.selectable')              │
 │                   │     → data-target-type(case|step) / -id / -label │
 │                   │  ③ "인용 댓글 달기" FAB 위치 계산·표시              │
 │ FAB 클릭         ▶│ composer 활성화 + 인용 미리보기 채움                  │
 │                   │  (quoteSourceKind, sourceId, label, text 보관)     │
 │ 본문 입력 + 제출                                                       │
 │ ─────────────────▶│ POST /comments {                                 │
 │                   │   body, quoteSourceKind: "STEP",                  │
 │                   │   quoteSourceId: 7,                               │
 │                   │   quoteSnapshot: "<드래그한 텍스트>"               │
 │                   │ } ─────────────────────────────────────────────▶│ requireRead
 │                   │                                                  │ validateQuoteSource(STEP, 7, caseId)
 │                   │                                                  │   step.errorCaseId == caseId ?
 │                   │                                                  │ Comment.create(snapshot truncate→500자)
 │                   │                                                  │ save
 │                   │ ◀────────── 201 CommentResponse ────────────────│
 │                   │  목록 갱신 + 새 댓글에 quote 뱃지 [Step 2] 표시      │
```

본문 드래그(CASE_BODY) 시:
- `quoteSourceKind: "CASE_BODY"`, `quoteSourceId: null`, `quoteSnapshot: "<드래그한 텍스트>"`
- BE 검증: `quoteSnapshot` 비어있지 않은지만 확인 (caseId 자체가 source).

"원문 보기" 점프 — 인용문 클릭 시:
```
FE: const target = quote.kind === 'STEP'
      ? document.querySelector(`[data-target-type="step"][data-target-id="${quote.sourceId}"]`)
      : document.querySelector('[data-target-type="case"]')   // CASE_BODY
    const idx = target.textContent.indexOf(quote.snapshot)
    if (idx >= 0) { range.scrollIntoView({behavior:'smooth'}); highlight() }
    else            { toast('원본을 찾을 수 없습니다 — 본문이 수정되었을 수 있어요') }
```

오류 케이스:
- step 이 다른 케이스 소속 → 400 `step N is not part of case M`
- CASE_BODY 인데 snapshot 없음 → 400 `CASE_BODY quote must include a snapshot`
- 권한 없음 → 403

### 4.3 답글 작성 (depth=1 강제)

```
User       FE                                  BE
 │ A 댓글의 "답글 달기" 클릭                          │
 │ ────────▶│ 답글 composer 열기                   │
 │ 입력 + 제출                                       │
 │ ────────▶│ POST /comments                       │
 │          │   { body, parentCommentId: A.id }   │
 │          │ ─────────────────────────────────▶ │ A.parentCommentId == null ?
 │          │                                     │   yes → parent=A.id (그대로)
 │          │                                     │ save
 │          │ ◀──── 201 (parentCommentId=A.id) ───│

—— 답글의 답글 시나리오 ——
User → A 의 답글 B 에 "답글 달기"
 │ ────────▶│ POST {body, parentCommentId: B.id}
 │          │ ─────────────────────────────────▶ │ B.parentCommentId == A.id (=답글)
 │          │                                     │ → resolveTopLevelParent → A.id
 │          │                                     │ save with parent=A.id
 │          │ ◀──── 201 (parentCommentId=A.id) ───│
 │          │ UI: B 옆이 아닌 A 의 children 끝에 표시 │
```

### 4.4 이모지 리액션 (Slack 스타일)

```
User       FE                                       BE
 │ 칩 👍 클릭(byMe=false)                               │
 │ ────────▶│ POST /comments/{id}/reactions {👍}    │
 │          │ ─────────────────────────────────▶ │ UNIQUE 검사 → 없으면 insert
 │          │                                     │ 그 emoji 의 reactions 다시 조회
 │          │ ◀── 200 { emoji:👍, count:N+1, userIds:[..], reactedByMe:true }
 │          │ 칩 강조 + 카운트 +1

—— 같은 사용자 또 클릭 ——
 │ ────────▶│ DELETE /comments/{id}/reactions/👍
 │          │ ─────────────────────────────────▶ │ 본인 row 삭제 (없으면 no-op)
 │          │ ◀── 200 { count:N, byMe:false }    │
```

### 4.5 도움됨 토글 + 사용자 목록 보기

```
User       FE                                        BE
 │ "⭐ 도움됨 4" 클릭                                     │
 │ ────────▶│ popover 열기 (이미 응답에 helpful.userIds 들어있음)
 │          │ FE 가 userIds 로 iam-api `/users/{id}` 조회(batch/cached)
 │          │ → 아바타 stack + 이름 목록 렌더
 │
 │ popover 의 "도움됨 표시" 클릭                          │
 │ ────────▶│ POST /comments/{id}/helpful           │
 │          │ ─────────────────────────────────▶ │ requireRead
 │          │                                     │ UNIQUE 검사 → 없으면 insert / 있으면 delete (토글)
 │          │ ◀── 200 { helpedByMe:true, count:5, userIds:[1,2,3,4,5] }
 │          │ stack/카운트/배지 갱신
```

### 4.6 수정 / 삭제

```
—— 수정 ——
User       FE                                  BE
 │ 본인 댓글 "수정" 클릭                              │
 │ ────────▶│ inline editor 표시                  │
 │ 본문 변경 + 저장                                   │
 │ ────────▶│ PATCH /comments/{id} {body}        │
 │          │ ───────────────────────────────▶  │ author == requester ?
 │          │                                    │ deletedAt != null → 400
 │          │                                    │ comment.edit(body) → editedAt 세팅
 │          │ ◀── 200 CommentResponse (editedAt) │
 │          │ "(편집됨 · ts)" 표시 추가

—— 삭제 (soft) ——
User → "삭제" 클릭
 │ ────────▶│ DELETE /comments/{id}              │
 │          │ ───────────────────────────────▶  │ author check
 │          │                                    │ reactions/helpful/mentions 정리(먼저)
 │          │                                    │ comment.softDelete() → body=(삭제된 본문), deletedAt 세팅
 │          │                                    │ save
 │          │ ◀── 204                            │
 │          │ 카드를 [삭제된 댓글] placeholder 로 교체
```

### 4.7 목록 조회 (트리 + cursor + filter)

```
FE                                                                BE
 │ GET /comments?sort=NEWEST&quoteKind=ALL&size=20                │
 │ ───────────────────────────────────────────────────────────▶  │ requireRead
 │                                                                │ 전체 댓글 fetch (top + replies)
 │                                                                │ filter / sort / cursor
 │                                                                │ pageIds + 그 replies 의 reactions/helpful/mentions 일괄 조회 (N+1 회피)
 │                                                                │ 트리 그루핑 + isAuthorOfCase 계산
 │                                                                │ quoteSummary 집계
 │ ◀── 200 { items: [{...comment, replies:[...]}], nextCursor, hasNext, quoteSummary }
 │ FE 가 트리 렌더 + 사이드 패널(quoteSummary)·필터 카운트 표시
```

### 4.8 댓글 공유 (anchor URL)

```
User → "공유" 클릭
   FE: navigator.clipboard.writeText( location.origin + path + '#comment-' + id )
       history.replaceState(null, '', '#comment-'+id)
       토스트 "링크 복사됨"

다른 사용자가 그 링크 열기:
   FE: window.onload → location.hash 확인 → 해당 #comment-{id} 로 scrollIntoView + 강조
```

---

## 5. API 엔드포인트 요약

```
POST   /api/v1/error-cases/{caseId}/comments                              ← suggestion 동시 첨부 가능
GET    /api/v1/error-cases/{caseId}/comments?sort=&quoteKind=&cursor=&size=
PATCH  /api/v1/error-cases/{caseId}/comments/{commentId}                  ← 본인만 (body)
DELETE /api/v1/error-cases/{caseId}/comments/{commentId}                  ← 본인만 soft

POST   /api/v1/error-cases/{caseId}/comments/{commentId}/helpful          ← 누구나 토글
POST   /api/v1/error-cases/{caseId}/comments/{commentId}/reactions  {emoji}
DELETE /api/v1/error-cases/{caseId}/comments/{commentId}/reactions/{emoji}

PATCH  /api/v1/error-cases/{caseId}/comments/{commentId}/suggestion/status  ← ★ Diff 제안 상태 토글 (케이스 owner 만)
```

응답 본문 형태:
```json
{
  "items": [
    {
      "id": 3, "errorCaseId": 28, "authorUserId": 5,
      "isAuthorOfCase": true,
      "parentCommentId": null,
      "body": "이 step 의 의견",
      "quote": { "kind":"STEP", "sourceId":7, "snapshot":"X", "truncated":false },
      "reactions": [{ "emoji":"👍", "count":4, "userIds":[1,2,3,4], "reactedByMe":false }],
      "helpful":   { "count":4, "userIds":[1,2,3,4], "helpedByMe":false },
      "mentions":  ["miso"],
      "editedAt": null, "deletedAt": null,
      "createdAt": "...", "updatedAt": "...",
      "replies": []
    }
  ],
  "nextCursor": "...", "hasNext": false,
  "quoteSummary": {
    "general": 2, "caseBody": 0,
    "steps":   [{ "sourceId": 7, "count": 1 }]
  }
}
```

---

## 6. 권한 매트릭스

| 작업 | 권한 |
|---|---|
| 댓글 조회 / 작성 / 답글 / 리액션 추가·해제 / 도움됨 토글 | **`ErrorCaseAccess.requireRead`** — Visibility 분기: PUBLIC=로그인 누구나 / WORKSPACE=멤버 / PRIVATE=owner |
| Diff 제안 *작성* (댓글 작성 시 첨부) | **케이스 read 권한** — 댓글 작성과 동일 |
| 댓글 수정 / 삭제(soft) | **작성자 본인만** |
| **Diff 제안 상태 변경 (PENDING ↔ APPLIED/REJECTED)** | **케이스 owner 만** — GitHub PR author 와 동치 (Visibility 와 무관) |
| 케이스 owner 표시 (`isAuthorOfCase=true`) | 백엔드 자동 계산 |
| 운영자/ADMIN 강제 삭제 | (미구현 — 후속) |

권한 정책 핵심: **WRITE+ 가 아닌 READ 권한자도 댓글 가능** — step/solution(케이스 WRITE 필요)과 다르게, 댓글은 토론 채널이라 더 가벼움.

---

## 7. 기능 인벤토리 — 구현된 것

| 기능 | 구현 위치 |
|---|---|
| 일반 댓글(NONE) | `CreateCommentUseCase` |
| 본문/Step 인용 + source 검증 (3종) | `CreateCommentUseCase.validateQuoteSource` |
| 인용 snapshot 500자 truncate | `Comment.truncateSnapshot` |
| 답글 depth=1 redirect | `CreateCommentUseCase.resolveTopLevelParent` |
| @멘션 파싱·적재 | `CreateCommentUseCase.MENTION_PATTERN` |
| 본인만 수정 + editedAt | `UpdateCommentUseCase` / `Comment.edit` |
| 본인만 soft delete + placeholder | `DeleteCommentUseCase` / `Comment.softDelete` |
| 이모지 리액션(UNIQUE 멱등) | `AddReactionUseCase` / `RemoveReactionUseCase` |
| 도움됨 모든 사용자 토글 | `ToggleHelpfulUseCase` |
| 정렬 NEWEST/OLDEST/HELPFUL | `ListCommentsUseCase.Sort` |
| 필터 ALL/GENERAL/CASE_BODY/STEP | `ListCommentsUseCase.QuoteKindFilter` |
| Cursor 페이징 (top-level) | `ListCommentsUseCase.applyCursor` (Base64URL `createdAt|id`) |
| 트리 응답 그루핑 | `ListCommentsUseCase.invoke` |
| quoteSummary 집계 (사이드 패널) | `ListCommentsUseCase.invoke` |
| isAuthorOfCase 자동 계산 | `CommentNode.toResponse(caseOwnerUserId)` |
| 케이스 삭제 cascade (댓글/reactions/helpful/mentions/suggestions) | `DeleteErrorCaseUseCase` |
| **★ Diff 제안 첨부 + 상태 변경** | `CreateCommentUseCase`(저장) · `UpdateSuggestionStatusUseCase`(케이스 owner 검증) · `ListCommentsUseCase`(suggestion batch fetch + diff lines) |
| **단순 line 단위 unified diff 렌더** | `CommentSuggestion.toDto()` (`CommentResponses.kt`) — old 전체 del + new 전체 add. HTML 의 `buildDiff` 동일 |
| Swagger (8 엔드포인트, 권한/예시 포함) | `CommentController` |

---

## 8. 함정·운영 노트

### 8.1 `softDelete` 와 bulk `@Modifying` 의 순서 함정 (잡았음)
`DeleteCommentUseCase` 에서 `softDelete()` + `save()` 를 *정리 작업 앞* 에 두면, 뒤따르는 bulk `@Modifying(clearAutomatically=true)` 가 persistence context 를 비워 status UPDATE 가 사라진다 (회원탈퇴 때와 동일 함정). **정리 → save 마지막** 순서로 고정.

### 8.2 Flyway 마이그레이션
신규 4 테이블 + `quote_source_kind` CHECK:
```sql
-- 본 테이블 + UNIQUE/INDEX 는 도메인 정의에 따라 ddl-auto 또는 Flyway 명시
ALTER TABLE error_case_comment
  ADD CONSTRAINT error_case_comment_quote_source_kind_check
  CHECK (quote_source_kind IN ('NONE','CASE_BODY','STEP'));
```
(2026-06-01) CODE_BLOCK 흡수 — 기존 row 가 'CODE_BLOCK' 인 경우 'CASE_BODY' 로 UPDATE 후 CHECK 적용. 로컬 점검 시 0건이라 단순 enum 좁힘으로 진행.

### 8.3 N+1 회피
list 응답에서 댓글 페이지의 모든 id 를 한번에 모아 `findReactionsByCommentIds(allIds)` 등으로 **단일 IN 쿼리** 1번씩. 답글까지 한 페이지 안에 함께 fetch.

### 8.4 사용자 표시 정보
응답에 `displayName`/`avatarUrl` 임베드 X. 프런트가 `/users/{id}` 로 batch 조회 + 캐시. cross-service 호출은 백엔드가 안 함(이전 결정 일관).

### 8.5 권한 누수 방지 (2026-06-01 갱신 — Visibility 도입 완료)
모든 mutation 에서 `requireRead` 호출. **`ErrorCaseAccess.requireRead` 가 `Visibility` 분기를 가짐**:
- `PUBLIC` → 로그인된 모든 사용자 통과 (개인 PUBLIC 케이스에 누구나 댓글 가능)
- `WORKSPACE` → 그 워크스페이스 멤버만
- `PRIVATE` → owner 만

워크스페이스 케이스를 `PUBLIC` 으로 승격하는 경로는 **워크스페이스 ADMIN 만**(`ErrorCaseAccess.requirePublicPromotion` — `CreateErrorCaseUseCase`·`UpdateErrorCaseUseCase` 에서 호출). 결정 8개 등재: `revisitable-decisions.md` §9.

---

## 9. 확장 포인트 (미구현)

| # | 항목 | 비고 |
|---|---|---|
| 1 | **인용 snapshot stale 표시** | 원본(case body / step body) 수정 시 ⚠️ 표시. 응답에 `quote.isStale: boolean` 추가. 비교는 hash 또는 updatedAt |
| 2 | **알림 라우팅** (Phase 3) | 멘션·내 댓글에 답글·내 케이스 새 댓글 → noti-api. `CommentMention` 이미 적재 |
| 3 | **댓글 → step 승격** | 토론에서 가치 있는 댓글을 step 으로 변환 (작성자/owner 한정). 본문 복사 + step.create |
| 4 | **운영자 강제 삭제 / 신고** | role 모델 별도 도입 후. 현재는 본인 삭제만 |
| 5 | **인용 카운트를 매칭 신호로 활용** | `quoteSourceId=stepX` 가 많은 step → 활발한 step → §4 Solution 매칭에 가중 |
| 6 | **GET single comment** | 현재 list 만. anchor URL 진입 시 해당 댓글 단건 + 컨텍스트 답글 응답 endpoint 가 있으면 1회 요청으로 점프 가능 |
| 7 | **사용자 batch 조회** (iam-api) | 트래픽이 늘면 `GET /users?ids=1,2,3` 가 자연. 댓글 list 에서 author/helpful/mention userId 일괄 조회 |

---

## 9-B. 인용 종류 단순화 결정 (2026-06-01) — CODE_BLOCK 흡수

**Before**: NONE / CASE_BODY / STEP / **CODE_BLOCK** (4종)
**After** : NONE / CASE_BODY / STEP (3종)

근거:
- 본문에 임베드된 `@snippet(id)` 토큰은 *본문의 일부* — CASE_BODY 가 자연스러운 출처.
- "어느 스니펫" 위치 점프는 BE 가 `quoteSourceId=snippetId` 로 명시 안 해도 FE 가 `quoteSnapshot` 첫 매치 검색으로 충분히 처리. drag → 자동 판정도 단순화(본문 안 어디든 CASE_BODY).
- 잃는 것: `quoteSummary.snippets[]` (스니펫별 토론 카운트). STEP 별 카운트가 훨씬 의미 있는 시그널이라 잃는 가치 작음.
- 얻는 것: enum 1개·검증 분기 1개·port 메서드 1개·DB CHECK 값 1개·filter 옵션 1개 제거. **`CodeSnippetRepositoryPort.findById(Long)` 와 `CreateCommentUseCase` 의 snippet 의존성 롤백** — 모듈 의존 그래프 단순화.
- DB 점검(2026-06-01): `SELECT count(*) FROM error_case_comment WHERE quote_source_kind = 'CODE_BLOCK'` → 0건 → 데이터 마이그레이션 없이 enum 좁힘.

stale 처리 경로 일관화: CODE_BLOCK 분리 시엔 *snippet 삭제 → dangling sourceId* 라는 별도 경로가 필요했지만, 흡수 후엔 CASE_BODY stale(*본문에서 못 찾음 → snapshot 만 표시*) 와 같은 경로로 자연 수렴.

---

## 9-A. BC 위치 결정 — 왜 `errorcase` sub-module 인가

**현재**: `core-api/errorcase/comment/` sub-module (errorcase BC 의 7번째 aggregate).

DDD 잣대로 봤을 때 comment 는 *진짜 애그리거트*(자체 id·invariants·lifecycle·authoritative state) 라 §검색/§매칭(derived read model) 과 달리 **분리한다면 별도 BC 로 가는 게 자연**. 다만 *지금* 분리할 정당성은 약함:
- 모든 의미가 errorcase 컨텍스트 안에서만 동작 (인용 source = case/step/snippet, 권한 = `ErrorCaseAccess`, cascade = case 삭제 시 정리).
- 사용자 여정이 "케이스 읽다가 토론" 단일.
- 다른 도메인(step/solution/blog/프로필 등)에 댓글이 *아직 없음*.

**분리 트리거** (충족 시 행동):
1. step·solution·publish-api(블로그)·공개 프로필에도 댓글 → **errorcase 와 분리된 `discussion` BC**(같은 서비스 안).
2. 모더레이션·신고·role 기반 hide 비대화 → 같은 (1) 방향.
3. 댓글 read 가 케이스 OLTP 의 2~3배 + 별도 DB 부하 분리 필요 → **`discussion-api` 별도 마이크로서비스**.
4. noti-api/소셜그래프 결합 폭증 (멘션 fan-out, 답글/리액션 푸시) → 같은 (3) 방향.
5. 외부 컨슈머용 댓글 API → (3).

**현재 코드가 이미 분리 친화**: case/step/snippet 에 *포트 인터페이스로만* 의존, `errorCaseId` 가 *그냥 Long*, 권한이 `ErrorCaseAccess` 라는 *공유 객체* 통해서. 트리거 도달 시 폴더 이동 + ACL 클래스 추가로 1-2시간 분리 가능. 결정 회고와 옵션 비교 전문은 [`revisitable-decisions.md`](revisitable-decisions.md) §8.

---

## 11. ★ Diff 제안 댓글 (GitHub Suggest 차용) — 2026-06-01 추가

### 11.1 UX 흐름 (FE 책임)

```
[코드블럭 - reviewableCode] (case body 의 임베드 snippet 또는 step 안의 markdown ```...```)
  ┌────────────────────────────────────────────────────┐
  │ gutter │   3 │  inventoryRepository.reserve(...)   │
  │        │   4 │  orderRepository.updateStatus(...)  │   ← 드래그 (3-4)
  │        │   5 │  return order;                      │
  └────────────────────────────────────────────────────┘
              │
              ▼
[composer 의 "Diff 제안" preview 활성화]
  - sourceType: SNIPPET (case body 의 @snippet 임베드) / MARKDOWN_CODE (step 본문 코드블럭)
  - sourceId: opaque 식별자 (FE 가 DOM dataset 에서 추출)
  - startLine=3, endLine=4
  - oldCode: 선택된 라인 텍스트 (= quoteSnapshot 동기)
  - newCode: 사용자가 textarea 에 입력
              │
              ▼
[POST /comments]  body + quote + suggestion 한꺼번에
              │
              ▼
[댓글 카드 아래 diff 박스 렌더]
  ┌─ Diff 제안 · 트랜잭션 범위 축소 ────────────────────┐
  │ snippet-a1b2c3d4 · 검토 대기                       │
  │  +2  -2                              [반영] [거부] │ ← 케이스 owner 만
  │ ─────────────────────────────────────────────────  │
  │ 126 │ - │ inventoryRepository.reserve(...)         │
  │ 127 │ - │ orderRepository.updateStatus(...)        │
  │     │ + │ inventoryService.reserveInShort(...)     │ │ 126
  │     │ + │ orderStatusService.markPaymentPending()  │ │ 127
  └────────────────────────────────────────────────────┘
```

### 11.2 데이터 모델 — `CommentSuggestion` (Comment 1:1)

| 필드 | 타입 | 비고 |
|---|---|---|
| `id` | Long | PK |
| `commentId` | Long | **UNIQUE** — 한 댓글에 최대 1개 제안 |
| `sourceType` | enum `SNIPPET` / `MARKDOWN_CODE` | 코드 출처 종류 |
| `sourceId` | String(128) | **opaque** — SNIPPET=`snippet.markerId` / MARKDOWN_CODE=FE 가 부여한 식별자(예: `step-2-code-1`) |
| `startLine`, `endLine` | Int | 라인 범위 (1-base, end ≥ start) |
| `oldCode`, `newCode` | TEXT | ≤ 10,000자. quoteSnapshot 과 oldCode 가 동기되는 게 권장 |
| `status` | enum `PENDING` / `APPLIED` / `REJECTED` | 작성 시 PENDING 자동 |
| `additions`, `deletions` | derived | 응답 시 newCode/oldCode 라인 수에서 계산 |
| `createdAt`, `updatedAt` | timestamp | |

### 11.3 확정 정책

| 축 | 정책 |
|---|---|
| 댓글당 제안 개수 | **최대 1개** (`UNIQUE(commentId)`) |
| 작성 시 status | **PENDING 자동** |
| sourceId 검증 | **opaque — BE 검증 X**. *제안* 이지 *반영 보장* 아니므로 약한 결합. dangling 시 FE 가 처리 |
| 상태 변경 권한 | **케이스 owner 만** (PR author 와 동치) |
| 자동 코드 반영 | X (Phase 1) — APPLIED 표시뿐, 실제 코드 변경은 작성자 수동 |
| 댓글 본문 수정 시 | suggestion 자체는 *변경 X*. body 만 변경(편집됨 마크). suggestion stale 가능성 — Phase 2 워크플로우 |
| 댓글 삭제(soft) 시 | suggestion **하드 삭제**(soft 의미 없음). reactions/helpful/mentions 와 같은 정리 순서 |
| diff lines 생성 | **BE 응답 시 단순 line-단위**(old 전체 del + new 전체 add). 진짜 LCS 아님 — 표시용. HTML 의 `buildDiff` 동일 |
| 제안 수정/되돌리기 | 댓글 작성자도 제안 자체는 수정 불가 (Phase 1) — 다시 제안하려면 새 댓글 |

### 11.4 통신 흐름 (제안 작성 → 케이스 owner 반영)

```
User       FE                                       BE
 │ 코드블럭 gutter 라인 3-4 드래그                          │
 │ ────────▶│ openSuggestionFromRange (DOM dataset)     │
 │          │   selectedSuggestion = { sourceType, ... }│
 │          │   selectedQuote = { kind:'CASE_BODY', text:oldCode }
 │          │ 제안 preview + 인용 미리보기 활성화           │
 │ after 코드 입력 + 댓글 작성 클릭                          │
 │ ────────▶│ POST /comments {                          │
 │          │   body, quoteSourceKind:"CASE_BODY",       │
 │          │   quoteSnapshot:oldCode,                   │
 │          │   suggestion:{ sourceType, sourceId, startLine, endLine, oldCode, newCode }
 │          │ }                                           │
 │          │ ─────────────────────────────────────────▶│ requireRead
 │          │                                            │ Comment.create + save
 │          │                                            │ CommentSuggestion.create(status=PENDING) + save
 │          │                                            │ 멘션 파싱
 │          │ ◀── 201 CommentResponse(suggestion + lines[])
 │          │ 댓글 카드 아래 diff 박스 렌더                │

—— 케이스 owner 가 반영/거부 ——
case-owner FE                                              BE
 │ "반영" 클릭                                              │
 │ ────────▶│ PATCH /comments/{id}/suggestion/status     │
 │          │   { status: "APPLIED" }                    │
 │          │ ─────────────────────────────────────────▶│ comment 조회 → errorCase.ownerUserId == requester ?
 │          │                                            │ suggestion.changeStatus(APPLIED)
 │          │ ◀── 200 SuggestionStatusResponse           │
 │          │ 배지 "반영됨" 으로 변경 + 토스트               │
```

### 11.5 sourceType 별 의미와 FE 식별

| sourceType | 의미 | FE 에서의 sourceId 부여 |
|---|---|---|
| `SNIPPET` | `CodeSnippet` 엔티티(독립 리소스). case body 또는 step body 에 `@snippet(markerId)` 로 임베드 | `data-source-id="snippet-<markerId>"` 또는 그냥 markerId. BE 는 opaque |
| `MARKDOWN_CODE` | step body 의 마크다운 ```...``` 코드블럭. 별도 엔티티 X — step body 의 일부 | `data-source-id="step-<stepId>-code-<블럭인덱스>"` 등 FE 가 부여한 의미상의 ID |

→ BE 는 sourceId 를 **opaque** 로 받고 sourceType + sourceId 쌍에 대한 인덱스만 만든다 (`ix_comment_suggestion_source`). dangling 시 FE 가 *"원본 코드를 찾을 수 없습니다"* 토스트 — quote stale 처리 패턴 일관.

### 11.6 응답 예시 (제안 첨부된 댓글)

```json
{
  "id": 207, "errorCaseId": 28, "authorUserId": 9,
  "body": "트랜잭션 범위를 줄이는 게 좋을 것 같아요.",
  "quote": { "kind":"CASE_BODY", "sourceId":null, "snapshot":"  inventoryRepository.reserve(...)\n  orderRepository.updateStatus(...)", "truncated":false },
  "suggestion": {
    "id": 31, "sourceType":"SNIPPET", "sourceId":"snippet-a1b2c3d4",
    "startLine":126, "endLine":127,
    "oldCode":"  inventoryRepository.reserve(...)\n  orderRepository.updateStatus(...)",
    "newCode":"  inventoryService.reserveInShortTransaction(...)\n  orderStatusService.markPaymentPending(...)",
    "status":"PENDING", "additions":2, "deletions":2,
    "lines":[
      {"type":"del","oldNo":126,"newNo":null,"code":"  inventoryRepository.reserve(...)"},
      {"type":"del","oldNo":127,"newNo":null,"code":"  orderRepository.updateStatus(...)"},
      {"type":"add","oldNo":null,"newNo":126,"code":"  inventoryService.reserveInShortTransaction(...)"},
      {"type":"add","oldNo":null,"newNo":127,"code":"  orderStatusService.markPaymentPending(...)"}
    ]
  }
}
```

### 11.7 확장 포인트 (Phase 2+)

| # | 항목 | 비고 |
|---|---|---|
| 1 | **자동 코드 반영** | APPLIED 시 `CodeSnippet.code` 또는 `Step.body` 를 newCode 로 자동 변경. 안전한 reviewer-merge 모델 + 변경 이력 추적 필요 |
| 2 | **진짜 LCS diff** | java-diff-utils 같은 라이브러리로 context 라인 포함. 현재는 단순 line 치환 |
| 3 | **제안 수정/되돌리기** | newCode 수정 endpoint, 작성자만 |
| 4 | **여러 제안 묶음** | 한 댓글에 여러 제안(현재 1:1) — 또는 한 PR 처럼 여러 댓글 묶음 |
| 5 | **stale 표시** | 원본(snippet code / step body) 수정 시 oldCode 와 불일치 → ⚠️ stale 배지 |
| 6 | **알림 라우팅** | PENDING 제안 생성 → 케이스 owner 알림. APPLIED → 작성자 알림 (Phase 3 noti-api) |
| 7 | **filter** | `quoteKind` 옆에 "Diff 제안만" 필터 — 제안 검토 화면 |

---

## 10. 관련 코드 인벤토리

도메인:
- `comment/domain/model/Comment.kt`, `CommentReaction.kt`, `CommentHelpful.kt`, `CommentMention.kt`, **`CommentSuggestion.kt`**
- `comment/domain/model/vo/QuoteSourceKind.kt`, **`SuggestionSourceType.kt`**, **`SuggestionStatus.kt`**

응용:
- `comment/application/command/CommentCommands.kt` (+ `UpdateSuggestionStatusCommand`, `CreateCommentCommand.SuggestionInput`)
- `comment/application/port/CommentRepositoryPort.kt` (5 도메인 통합 — comment/reaction/helpful/mention/suggestion)
- `comment/application/usecase/`:
  - `CreateCommentUseCase`(인용 검증 + depth=1 redirect + 멘션 파싱 + **suggestion 동시 저장**)
  - `UpdateCommentUseCase` · `DeleteCommentUseCase`(soft, suggestion cascade)
  - `ListCommentsUseCase`(트리/cursor/sort/filter/quoteSummary + **suggestion batch fetch**)
  - `ToggleHelpfulUseCase`(모든 사용자)
  - `AddReactionUseCase` · `RemoveReactionUseCase`(Slack 스타일 UNIQUE)
  - **`UpdateSuggestionStatusUseCase`**(케이스 owner — PENDING ↔ APPLIED/REJECTED)
- 예외: `CommentNotFoundException` · `CommentAccessDeniedException` · `CommentInvalidException`

인프라:
- `comment/infrastructure/jpa/entity/`: **5 엔티티** (Comment/Reaction/Helpful/Mention/**Suggestion**)
- `comment/infrastructure/jpa/{Comment,CommentReaction,CommentHelpful,CommentMention,CommentSuggestion}JpaRepository.kt`
- `comment/infrastructure/jpa/adapter/CommentRepositoryAdapter.kt`

표현:
- `comment/presentation/web/CommentController.kt` (**8 엔드포인트** + Swagger 동시 — Diff 제안 상태 PATCH 추가)
- `comment/presentation/web/dto/request/CommentRequests.kt` (+ `CreateCommentRequest.SuggestionRequest`, `UpdateSuggestionStatusRequest`)
- `comment/presentation/web/dto/response/CommentResponses.kt` + mappers (`toQuoteDto`, `toResponse`, **`CommentSuggestion.toDto()`** — diff lines 생성)

연결:
- `case/application/usecase/DeleteErrorCaseUseCase.kt` — 댓글 cascade 추가
- `shared/application/usecase/ErrorCaseAccess` — 권한 헬퍼 재사용

> 2026-06-01: CODE_BLOCK 흡수로 `CodeSnippetRepositoryPort.findById(Long)` 와 `CreateCommentUseCase` 의 `snippetRepository` 의존성 *제거*. 본문 안 스니펫 인용도 CASE_BODY 로 통합되며, "원문 보기" 점프는 FE 의 `quoteSnapshot` 첫 매치 검색으로 충분히 처리.

---

## 11. 참고

- **코드 구현 가이드** (레이어별 클래스 카탈로그·호출 그래프·DB 매핑·함정): [`comment-implementation.md`](comment-implementation.md)
- 설계 결정 회고/대안: [`revisitable-decisions.md`](revisitable-decisions.md) §7
- 댓글 BC 위치 결정: [`revisitable-decisions.md`](revisitable-decisions.md) §8
- 도메인 전체 맥락: [`errorcase-domain.md`](errorcase-domain.md)
- 첨부/스니펫 라이프사이클(이 문서와 같은 스타일): [`attachment-lifecycle.md`](attachment-lifecycle.md)
- HTML 디자인 컨셉(시각화): `~/Downloads/Concept-Comment-Discussion.html` / `~/Downloads/BlogConcept33_gutter_diff_code_drag_quote_fixed.html` (개인 작업물)

# insight-api · Activity Grass — 기획 + 설계

> GitHub 의 contribution graph 를 모티프로 사용자의 *문제 해결 활동* 을 한 페이지에 시각화.
> 본 문서는 1) 무엇을 카운트할지, 2) 어떻게 모을지, 3) 어떻게 보여줄지를 결정하는 **기획 + 1차 설계** 다.

---

## 1. 컨셉

| GitHub Contributions | Error Archive Activity Grass |
|---|---|
| commit / PR / issue | error case · step · solution · comment · reaction · helpful |
| 53주 × 7일 그리드 | 동일 (최근 1년) |
| 5단계 색 농도 | 동일 — *점수* 기준 (단순 count 아님) |
| public 프로필 | public/private 토글 가능 |
| — | **추가**: streak (연속 활동 일수) + 활동 종류 breakdown + “가장 빛난 날” |

> 가장 큰 차이: **단순 카운트 대신 가중치 점수**. 댓글 100개 vs 케이스 1개를 동일하게 취급하면 활동 의미가 희석된다.

---

## 2. 활동 종류 + 가중치 (proposal)

| Code | 출처 서비스 | 가중치 | 멱등 키(이벤트 dedupe) |
|---|---|---|---|
| `CASE_CREATED`         | core-api  | **5** | `case:{id}` |
| `CASE_PUBLISHED`       | core-api  | 3 | `case-pub:{id}` |
| `STEP_ADDED`           | core-api  | 2 | `step:{id}` |
| `SOLUTION_ADDED`       | core-api  | **5** | `solution:{id}` |
| `COMMENT_POSTED`       | core-api  | 2 | `comment:{id}` |
| `COMMENT_REACTION`     | core-api  | 1 | `react:{commentId}:{emoji}` |
| `COMMENT_HELPFUL`      | core-api  | 1 | `helpful:{commentId}:{userId}` |
| `FOLLOWED_USER`        | iam-api   | 1 | `follow:{followerId}:{followeeId}` |

> 점수는 결정이 아니라 *시작점* — 운영하며 분포 보고 튜닝. 정책 변경 시 과거 데이터는 *raw 이벤트 기반 재계산* 가능하도록 raw 보존(아래 §4).

### 색 단계 (5단계)

점수 합 → level 매핑. **상대 분위(quantile) + 절대 threshold 혼합** 으로 *사용자별 자동 스케일링* 을 한다(GitHub 도 비슷).

```
level 0: 0       (활동 없음)
level 1: 1~25%  사용자 본인 1년치 *비영(非零)* 일의 25분위 이하
level 2: 25~50%
level 3: 50~75%
level 4: 75~100% (가장 빛난 날)
```

> 신규/저활동 사용자도 *상대적인 잔디 농도 변화* 가 보인다는 장점.
> 절대값으로 다른 사용자와 비교가 필요하면 `comparison-mode=absolute` 쿼리 파라미터로 토글.

---

## 3. 데이터 수집 — 이벤트 흐름

```
core-api / iam-api  ─┐ Kafka publish
                     │   topic: user-activity.v1   key: userId   value: {type, occurredAt, idempotencyKey, meta}
                     ▼
                ┌────────────────┐
                │  insight-api   │  @KafkaListener
                │  Consumer      │
                └────────┬───────┘
                         ▼
              ┌─────────────────────────┐
              │  insight_activity_event │  ← raw, 멱등 키로 INSERT IGNORE
              └────────────┬────────────┘
                           ▼ daily aggregate (트리거 또는 batch)
              ┌──────────────────────────┐
              │  insight_activity_daily  │  사용자 × 날짜 × 카운트/점수
              └──────────────────────────┘
                           ▲
                           │ GET /users/{id}/activity-grass
                  FE (잔디 페이지) — 1년치 daily 조회
```

### 왜 Kafka 인가
- noti-api 와 *같은 토폴로지* (이미 broker 운영 중)
- 출처 서비스가 *fire-and-forget* — 활동 이벤트가 동기 호출처럼 응답 시간을 잡아먹지 않아야 함
- insight-api 가 잠시 꺼져도 broker 가 retain — 재기동 시 끊김 없이 따라잡음

### 멱등성
- consumer 가 at-least-once → 같은 이벤트가 두 번 올 수 있음
- `idempotencyKey` 가 `insight_activity_event.unique(idempotency_key)` 제약 → 중복 INSERT 무시

### 시계 관리
- `occurredAt` 은 *발생 측 서비스의 UTC*. insight-api 는 절대 자기 시계로 일자 결정 X
- 일자 결정 시 **사용자의 timezone** 적용 (iam-api 의 user preferences) — 한국 시간 23:30 활동이 *오늘 칸* 에 떨어져야 함

---

## 4. DB 스키마 (PostgreSQL)

### `insight_activity_event` — raw

| Column | Type | Note |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `user_id` | BIGINT NOT NULL | |
| `type` | VARCHAR(32) NOT NULL | enum string |
| `occurred_at` | TIMESTAMPTZ NOT NULL | 발생측 UTC |
| `score` | SMALLINT NOT NULL | publish 시점의 가중치 (정책 회귀 가능) |
| `idempotency_key` | VARCHAR(128) NOT NULL UNIQUE | dedupe |
| `meta_json` | JSONB | 추후 breakdown/링크용 |
| `created_at` | TIMESTAMPTZ NOT NULL DEFAULT now() | insight-api 수신 시각 |

- 인덱스: `(user_id, occurred_at)` — 일자 재집계용
- 보존: 13개월 (잔디는 12개월 + 월말 갱신용 +1)

### `insight_activity_daily` — aggregate

| Column | Type | Note |
|---|---|---|
| `user_id` | BIGINT NOT NULL | |
| `activity_date` | DATE NOT NULL | *사용자 timezone 기준* |
| `event_count` | INT NOT NULL | type 무관 총 건수 |
| `score_sum` | INT NOT NULL | 가중치 합 |
| `breakdown_json` | JSONB NOT NULL | `{CASE_CREATED:2, COMMENT_POSTED:7, ...}` |
| `updated_at` | TIMESTAMPTZ NOT NULL | |

- PK: `(user_id, activity_date)`
- 인덱스: `(user_id, activity_date DESC)` — 잔디 슬라이스 조회

> ~~`insight_user_visibility`~~ — *제거됨 (v0.2)*. 모든 잔디는 공개. 필요해지면 별도 BC 또는 본 BC 에 재도입.

---

## 5. API

### 외부 (gateway 경유, aud=insight-api)

| Method | Path | 책임 |
|---|---|---|
| GET   | `/api/v1/users/me/activity-grass?from=&to=&mode=` | 본인 잔디 (default: 최근 1년, mode=relative) |
| GET   | `/api/v1/users/{userId}/activity-grass?from=&to=&mode=` | 다른 사용자 잔디 — *모든 사용자의 잔디는 공개* (인증만 필요) |

> 잔디 공개/비공개 토글은 의도적으로 두지 않음. 사용자 통제권을 위한 가치 대비 운영 복잡도가 커서 제외 (필요 시 별도 BC 로 재도입).

### 응답 DTO

```json
{
  "rangeFrom": "2025-06-12",
  "rangeTo":   "2026-06-12",
  "timezone":  "Asia/Seoul",
  "days": [
    { "date": "2026-06-12", "count": 4, "score": 11, "level": 3,
      "breakdown": { "COMMENT_POSTED": 3, "CASE_CREATED": 1 } }
  ],
  "streak": { "current": 7, "longest": 41, "currentStartDate": "2026-06-06" },
  "totals": { "events": 1240, "score": 3412, "activeDays": 218 },
  "bestDay": { "date": "2026-02-14", "score": 38 },
  "thresholds": { "mode": "relative", "l1": 1, "l2": 4, "l3": 9, "l4": 18 }
}
```

> `days` 는 *비활동 일도 포함* — FE 가 53×7 그리드를 그대로 그리도록.

### Internal (gateway 미라우팅)

| Method | Path | 호출자 | 책임 |
|---|---|---|---|
| (없음) | — | — | 모든 in-bound 는 Kafka. internal HTTP endpoint 불필요 |

---

## 6. 모듈 구조 (hexagonal)

```
insight-api/
  src/main/kotlin/.../insightapi/
    activity/
      domain/
        ActivityEvent.kt           # raw 이벤트 aggregate
        ActivityDaily.kt           # 일별 집계 aggregate
        ActivityType.kt            # enum + 가중치 정책
        Streak.kt                  # 연속 일수 계산 도메인 서비스
      application/
        port/
          ActivityEventRepositoryPort.kt
          ActivityDailyRepositoryPort.kt
          UserTimezonePort.kt      # iam-api 호출 (timezone 조회)
          VisibilityRepositoryPort.kt
        usecase/
          IngestActivityEventUseCase.kt    # consumer 가 호출
          GetActivityGrassUseCase.kt
          GetStreakUseCase.kt
          UpdateVisibilityUseCase.kt
      infrastructure/
        jpa/                                # entity + JpaRepository + adapter
        kafka/
          ActivityEventConsumer.kt          # @KafkaListener user-activity.v1
          KafkaConsumerConfig.kt            # Boot 4 수동 빈
        iam/
          IamUserTimezoneAdapter.kt         # /internal/users/{id}/preferences
      presentation/
        web/
          ActivityGrassController.kt
          VisibilityController.kt
    shared/
      config/SecurityConfig.kt              # internal JWT verifier
```

---

## 7. 보안

- 외부 GET 의 viewer = 사용자 JWT 의 sub (gateway 가 internal JWT 발급)
- 공개 GET (`/users/{userId}/...`) 은 *grass_public=true* 한 경우만, 아니면 403
- 본인 PATCH 는 항상 허용
- Kafka 이벤트는 *서버측에서 publish* 라 사용자 위조 불가 — 단 actor userId 검증을 *발생 서비스* 가 책임

---

## 8. FE 디자인 — "Debug Pulse"

> **컨셉**: 서비스 정체성이 *에러를 다룬다* → 단순 잔디(녹색 단조) 대신
> **디버거 콘솔 룩 + "error → resolved" 색 스토리** 로 잔디 한 칸마다 *그 날의 디버깅 결말* 을 보여준다.
> 컨셉은 GitHub 의 *농도 표현* 을 그대로 빌리되, 색이 의미를 갖는 5단계 스펙트럼이 핵심.

### 8-1. 베이스 톤

- 다크 콘솔 기본 (`#0e0f12`), 라이트 모드는 옵션
- 헤더·통계·코드성 라벨은 **JetBrains Mono**, 본문은 Inter
- 헤더는 터미널 프롬프트 표기: `$ debug-pulse▮` (커서 blink)
- 라벨 곳곳 `// comment` 표기로 IDE 톤 유지

### 8-2. 색 스케일 (다크 기준)

| level | 의미 | 색 | 정서 |
|---|---|---|---|
| 0 | idle | `#1a1c21` | 정적 — 활동 없음 |
| 1 | errored | `#7f1d1d` (burn red) | 단서 발견 |
| 2 | debugging | `#b45309` (amber) | 깊이 파고드는 중 |
| 3 | progressing | `#0d9488` (teal) | 해결의 실마리 |
| 4 | **RESOLVED** | `#10b981` + soft glow | 백열 — 해결됨 |

> 라이트 모드는 같은 hue 의 *명도만 조정* — `level 1=#fecaca`, `level 4=#10b981` (글로우 약화). 단계 의미는 동일.

### 8-3. 그리드

- 53열(week) × 7행(요일 일~토)
- 한 셀: `11px × 11px`, gap `3px`, radius `2px`
- `level 4` 셀은 `box-shadow: 0 0 5px rgba(16,185,129,.55)` 글로우
- legend 양 끝에 `error → resolved` 화살표 라벨

### 8-4. tooltip — 콘솔 로그 라인

```
[2026-06-12]  RESOLVED 4 stacks · score=11
[2026-04-01]  debugging 2 stacks · score=6
[2026-03-22]  · no activity
```

색 코딩:
- 라벨 `RESOLVED` / `progressing` → 에메랄드
- `debugging` → 앰버
- `errored` → 적색
- 날짜 brackets, `·` 구분자 → muted gray

### 8-5. 셀 클릭 → day panel (터미널 출력 룩)

```
$ debug-pulse --date=2026-06-12      // level=RESOLVED · score=11
  ▸ [RESOLVED] case.created × 1
  ▸ [RESOLVED] step.added × 2
  ▸ [RESOLVED] comment.posted × 1
```

### 8-6. 상단 4 카드 (ANSI dot)

각 카드 좌상단에 작은 dot — `red / amber / teal / emerald` 글로우로 4 단계 색을 통계 카드에서도 반복:
- 🔴 `current streak`
- 🟡 `longest streak`
- 🟢-teal `active days`
- 🟢-emerald `resolutions` (총 활동 + 점수)

### 8-7. 사이드 카드

- **activity breakdown** — 활동 종류 가로 bar (이름은 `case.created`, `comment.posted` 같이 *코드성 도트 표기*)
- **weekday avg score** — 요일별 평균 점수. fill 은 *error → resolved gradient* (앰버→청록→에메랄드) 로 그라데이션 채움

### 8-8. 인터랙션 디테일

- 화살표키로 셀 이동 가능 (접근성)
- 비활동 일 hover → `· no activity`
- 모바일: 그리드는 가로 스크롤, sticky 요일 레이블
- relative ↔ absolute 토글 — 자기 분포 기반 / 다른 사용자와 비교용

### 8-9. 네이밍 / 톤

- 페이지 타이틀: **Debug Pulse** (잔디 = 디버깅의 맥박)
- "contributions" → **"resolutions"** / "stacks tamed"
- "best day" → **"▲ peak"**
- "Activity" / "Grass" 단어는 페이지에서 제거 — 도메인 언어로 일관

---

## 9. 의도된 미구현 / 추후

| 영역 | 비고 |
|---|---|
| 가중치 정책 튜닝 | 운영 데이터 모이면 분포 보고 조정 |
| 친구 비교 leaderboard | 별도 endpoint, 권한 모델 정리 후 |
| Achievements (배지) | 100일 streak / 첫 솔루션 / ... — 별도 BC 가 적절 |
| Weekly digest 이메일 | noti-api 의 weekly digest 토글과 연동 |
| Activity replay | raw 이벤트로 가중치 재계산해 history 그래프 |
| 활동 종류별 grass overlay | 한 화면에 두 종류 잔디 비교 (case-only vs comment-only) |

---

## 10. 검증 mock (playground)

`playground/activity-grass.html` — 본 문서의 그리드/색 규칙/streak/breakdown UI 를 mock 데이터로 **그대로 렌더링**. 백엔드 없이 디자인 결정을 미리 확인 가능.

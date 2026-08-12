# insight-api · Implementation Overview

> 사용자의 *문제 해결 활동* 을 잔디로 시각화 ("Debug Pulse").
> 본 문서는 **현재 구현된 코드** 의 매핑 + 미구현 영역을 한 페이지에 모은다.
> 설계 사상/기획은 `activity-grass-design.md` 참조 — 본 문서는 *지금 무엇이 동작하는가* 를 다룬다.

---

## 1. 큰 그림

```
[ core-api ]         [ iam-api ]
case.created          user.followed
comment.posted             │
   …                       │
   └────┬──────────────────┘
        ▼   *⚠ 미구현*  Kafka producer (다음 단계)
   topic: user-activity.v1   key: userId   value: JSON
        │
        ▼
 ┌────────────────────────────────────────────────────────┐
 │  insight-api (port 8083)                               │
 │   ① ActivityEventConsumer  (@KafkaListener)            │
 │           │                                            │
 │           ▼   IngestActivityEventUseCase  (@Transactional)
 │   ② ActivityEventRepositoryAdapter.insertIfAbsent      │
 │           │                                            │
 │           ▼  (raw 가 새로 들어간 경우만)               │
 │   ③ IamUserTimezoneAdapter.fetch(userId) → ZoneId      │
 │           │  (60s 메모리 캐시 + UTC fallback)          │
 │           ▼                                            │
 │   ④ ActivityDailyRepositoryAdapter.increment           │
 │       (native ON CONFLICT + jsonb_set, atomic)         │
 └────────────────────────────────────────────────────────┘

        (조회 시점)

 FE → gateway → insight-api
   ⑤ GetActivityGrassUseCase
       └─ visibility check → daily 365 row → days fill
          → Thresholds (relative quantile / absolute)
          → Streak.compute(today, activeDates)
   ⑥ ActivityGrassResponse(rangeFrom, rangeTo, days[], streak, totals, bestDay, thresholds)
        │
        ▼
   ⑦ playground/activity-grass.html — 53×7 grid + 통계 4 카드 + breakdown
```

---

## 2. 도메인 모델

위치: `org.studieojavry.insightapi.activity.domain`

| Aggregate / 도메인 객체 | 책임 | 핵심 메서드 / 필드 |
|---|---|---|
| `ActivityType` (enum 8종) | 가중치를 매핑할 활동 종류 | `CASE_CREATED, CASE_PUBLISHED, STEP_ADDED, SOLUTION_ADDED, COMMENT_POSTED, COMMENT_REACTION, COMMENT_HELPFUL, FOLLOWED_USER` + `fromCodeOrNull(code)` |
| `ActivityEvent` | 출처 서비스가 publish 한 활동 1건 (raw, 진실의 원천) | `create(userId, type, occurredAt, score, idempotencyKey, metaJson)` — 검증 포함 |
| `ActivityDaily` | 사용자 × *사용자 timezone 일자* 별 집계 | `userId, activityDate, eventCount, scoreSum, breakdown(Map<String,Int>), updatedAt` |
| ~~`Visibility`~~ | *제거됨* — 모든 잔디 공개 (v0.2) | — |
| `Streak` (값 객체) | 오늘 기준 거꾸로 연속 일수 + 1년치 최장 | `compute(activeDates, today)` — 오늘 비활동 시 어제부터 허용 (GitHub 정책) |
| `LevelCalculator` + `Thresholds` + `GrassMode` | 5단계 색 결정 | `compute(scores, mode)` → relative quantile (25/50/75) 또는 absolute 고정 |

### DB 매핑 (PostgreSQL)

| 테이블 | PK | 인덱스 | 비고 |
|---|---|---|---|
| `insight_activity_event` | `id BIGSERIAL` | `UNIQUE(idempotency_key)`, `ix_user_time(user_id, occurred_at)` | raw. 보존 13개월(미구현 — 정책만 있음) |
| `insight_activity_daily` | `(user_id, activity_date)` | `ix_activity_daily_user_date(user_id, activity_date DESC)` | jsonb breakdown |

---

## 3. API

### 3-1. 외부 (gateway 경유, aud=insight-api)

| Method | Path | 책임 | Controller |
|---|---|---|---|
| GET   | `/api/v1/users/me/activity-grass?from=&to=&mode=` | 본인 잔디. default 최근 365일, `mode=relative` (default) \| `absolute` | `ActivityGrassController` |
| GET   | `/api/v1/users/{userId}/activity-grass?from=&to=&mode=` | 다른 사용자 잔디. **모든 잔디 공개** — 인증만 필요 | `ActivityGrassController` |

> 잔디 공개/비공개 토글은 v0.1 까지 있었으나 v0.2 에서 제거 (`Visibility` 도메인 + endpoint + 테이블 모두 삭제). 필요 시 추후 재도입.

> **미구현**: `GET /api/v1/users/me/activity-grass/streak` — 현재는 메인 잔디 응답의 `streak` 필드로 한 번에 제공.

### 3-2. 응답 DTO

```json
{
  "rangeFrom": "2025-06-13",
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

- `days` 는 *비활동 일 포함* — FE 가 53×7 그리드를 그대로 그릴 수 있게.
- `thresholds` 가 응답에 박혀 옴 — FE/디바이스/캐시 간 색 일관성.

### 3-3. Internal (gateway 미라우팅)

| Method | Path | 호출자 | 호출됨 | 책임 |
|---|---|---|---|---|
| GET | iam-api `/internal/users/{userId}/preferences` | insight-api | iam-api | `{timezone, language}` 반환 — 잔디 일자 결정용 |

> insight-api 자체에는 **internal endpoint 없음**. 모든 ingest 는 Kafka 통해 들어옴.

---

## 4. 이벤트 흐름 — 코드 매핑

### 4-1. ingest path (Kafka → DB)

```
ActivityEventConsumer.onEvent(value: String)
  └─ ObjectMapper → ActivityMessage
  └─ IngestActivityEventUseCase.invoke(Input)
        @Transactional
        ├─ ActivityType.fromCodeOrNull(typeCode) — unknown 이면 drop + log
        ├─ score 결정: msg.score>0 이면 그 값, 아니면 weights.weightOf(type) (yml 정책)
        ├─ ActivityEvent.create(...) — validate
        ├─ ActivityEventRepositoryAdapter.insertIfAbsent
        │     ├─ existsByIdempotencyKey 사전 체크
        │     ├─ save → INSERT
        │     └─ DataIntegrityViolationException catch → null (race 흡수)
        ├─ if null (중복) → return (debug log)
        ├─ IamUserTimezoneAdapter.fetch(userId) → ZoneId
        │     ├─ 60s 메모리 캐시 hit 우선
        │     ├─ iamApi.get("/internal/users/{id}/preferences")
        │     └─ 실패/404/타임아웃 → ZoneId.of("UTC")
        ├─ occurredAt.atZone(tz).toLocalDate() → activityDate
        └─ ActivityDailyRepositoryAdapter.increment
              └─ JdbcTemplate.update("""
                   INSERT INTO ... VALUES (...)
                   ON CONFLICT (user_id, activity_date) DO UPDATE
                   SET event_count   = ... + 1,
                       score_sum     = ... + EXCLUDED.score_sum,
                       breakdown_json = jsonb_set(...) — type별 count + 1,
                       updated_at    = ...
                 """)
```

### 4-2. read path (조회)

```
gateway → HeaderInjectionFilter
  └─ uri.contains("/activity-grass") → audience=insight-api
ActivityGrassController.myGrass
  └─ GetActivityGrassUseCase.invoke
        @Transactional(readOnly = true)
        ├─ viewer != target 면 VisibilityRepositoryPort.findByUserId
        │     └─ grassPublic=false → AccessDeniedException (Controller 가 403 변환)
        ├─ tz = userTimezone.fetch(targetUserId); today = LocalDate.now(tz)
        ├─ rangeFrom = from ?: today.minusDays(364); rangeTo = to ?: today
        ├─ dailyRepository.findInRange(targetUserId, rangeFrom, rangeTo)
        ├─ generateSequence(rangeFrom..rangeTo) — 비활동 일도 Day 채움
        ├─ LevelCalculator.compute(scores, mode) → Thresholds
        ├─ Streak.compute(activeDates, today)
        └─ Result(rangeFrom, rangeTo, timezone, days, streak, totals, best, mode, thresholds)
  └─ ActivityGrassResponse 매핑
```

---

## 5. 가중치 정책 + 색 단계

### 5-1. 가중치 (`insight.activity.weights` in yml)

| Type | 가중치 | publish 멱등 키 패턴 (제안) |
|---|---|---|
| `CASE_CREATED`     | **5** | `case:{id}` |
| `CASE_PUBLISHED`   | 3 | `case-pub:{id}` |
| `STEP_ADDED`       | 2 | `step:{id}` |
| `SOLUTION_ADDED`   | **5** | `solution:{id}` |
| `COMMENT_POSTED`   | 2 | `comment:{id}` |
| `COMMENT_REACTION` | 1 | `react:{commentId}:{emoji}` |
| `COMMENT_HELPFUL`  | 1 | `helpful:{commentId}:{userId}` |
| `FOLLOWED_USER`    | 1 | `follow:{followerId}:{followeeId}` |

- raw event 의 `score` 컬럼은 *publish 시점 값* 그대로 저장 — 정책 회귀 시 raw 재처리 가능
- 새 활동 추가 = enum + yml weights + publisher 3 곳 동시 갱신

### 5-2. 색 단계 (`LevelCalculator`)

- `RELATIVE` (default): 사용자의 1년치 *비영* 점수 분포 25/50/75 분위 + `l1=1` (절대 컷)
- `ABSOLUTE`: 고정 `(l1=1, l2=5, l3=12, l4=24)`
- 활동 없는 사용자도 결정적 결과 — 모두 level 0

자세한 trade-off 는 design doc §2 + (전 대화의 *상대 분위 + 절대 threshold 혼합* 설명).

---

## 6. 보안 (internal JWT)

| 서비스 | 본 서비스 측 역할 |
|---|---|
| gateway       | issuer (aud=insight-api 발급) |
| core-api      | issuer — 추후 publisher 적재 시 사용 (현재는 미사용) |
| iam-api       | **insight-api 의 issuer 가 보낸 토큰을 검증** (`known-issuers.insight-api` 키 등록됨) |
| insight-api   | **verifier** (gateway, core-api 토큰 검증) + **issuer** (iam-api 로 timezone 조회 시 발급, sub=`system`) |

키:
- `insight-api-local-1` — RSA 2048. local 에선 noti-api 와 *같은 keypair* 재사용 (iss 클레임으로 분기, 키 자체는 동일 — 로컬 단순화)
- 운영 전환 시 ENV / secret manager 로 분리 필요

권한 정책:
- 본인 / 타인 잔디 모두 인증된 사용자라면 누구나 조회 가능
- v0.1 까지 있던 `Visibility` 토글은 v0.2 에서 제거

---

## 7. 설정 (yml + ENV)

### insight-api `application.yml` (base)

```yaml
spring:
  application: { name: insight-api }
  profiles:    { default: local }
server:
  port: 8083
insight:
  iam-api:
    base-url: ${INSIGHT_IAM_API_BASE_URL:http://localhost:8080}
  activity:
    weights:
      CASE_CREATED: 5
      CASE_PUBLISHED: 3
      STEP_ADDED: 2
      SOLUTION_ADDED: 5
      COMMENT_POSTED: 2
      COMMENT_REACTION: 1
      COMMENT_HELPFUL: 1
      FOLLOWED_USER: 1
```

### insight-api `application-local.yml`

```yaml
server: { port: 8083 }
spring:
  config: { activate: { on-profile: local } }
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9094}
    consumer:
      group-id: insight-api
      auto-offset-reset: earliest
  datasource:
    url: jdbc:postgresql://localhost:5435/insight_db
    username: insight; password: insight_local_pw
  jpa: { hibernate: { ddl-auto: update } }
internal-auth:
  issuer:   { name: insight-api, key-id: insight-api-local-1, private-key-pem: <RSA 2048> }
  verifier: { audience: insight-api, known-issuers: { gateway, core-api } }
```

### insight-api `src/test/resources/application-test.yml`

- H2 in-memory (PostgreSQL mode)
- Kafka broker URL = `localhost:1` (broker 없이도 빈 등록만 — listener 가 *연결 실패* 만 로깅하고 컨텍스트는 정상)
- 자체 internal-auth 키 — 외부 의존성 0

---

## 8. 인프라 (`insight-api/docker-compose.yml`)

| 컨테이너 | 포트 | 비고 |
|---|---|---|
| `insight-postgres` (postgres:16-alpine) | 5435 → 5432 | `insight_db` |
| Kafka broker | — | **noti-api 의 docker-compose 가 띄운 broker 공유** (`localhost:9094`) |

> Kafka broker 를 두 번 띄우지 않게 하기 위한 결정. noti-api 의 compose 를 먼저 띄우고 insight-api 는 그 broker 에 연결.

서비스 포트 매트릭스 (전체):

| 서비스 | 포트 | 역할 |
|---|---|---|
| gateway   | 8000 | edge — Authorization → X-Internal-Auth 변환 |
| iam-api   | 8080 | 사용자 / OAuth / preferences |
| core-api  | 8081 | errorcase / step / solution / comment |
| noti-api  | 8082 | inbox / settings / device tokens / Kafka consumer |
| **insight-api** | **8083** | **잔디 / 활동 / Kafka consumer** |

---

## 9. e2e 검증 흐름 (현재 가능한 부분)

1. `docker compose -f noti-api/docker-compose.yml up -d kafka kafka-ui` — broker + UI 기동
2. `docker compose -f insight-api/docker-compose.yml up -d` — insight-postgres 기동
3. `./gradlew :insight-api:bootRun` — 8083 기동
4. **수동 메시지 publish 로 잔디 채우기** (publisher 가 아직 없으므로):
   ```bash
   docker exec -it noti-kafka /opt/kafka/bin/kafka-console-producer.sh \
     --bootstrap-server kafka:9092 --topic user-activity.v1 \
     --property "parse.key=true" --property "key.separator=:"
   # 입력 예
   42:{"userId":42,"type":"CASE_CREATED","occurredAt":"2026-06-12T05:23:11Z","score":5,"idempotencyKey":"case:1001"}
   ```
5. **kafka-ui (http://localhost:8027)** 에서 `user-activity.v1` 메시지 등장 확인
6. **DB 확인** — `insight_activity_event` 1 row, `insight_activity_daily` 1 row, `breakdown_json={"CASE_CREATED":1}`
7. **API 조회** — `curl -H "Authorization: Bearer <user42 JWT>" http://localhost:8000/api/v1/users/me/activity-grass` → 그 날 셀에 level ≥ 1
8. **playground/activity-grass.html** 를 실 데이터로 wiring (mock 분기 제거) — 현재는 mock 만

---

## 10. 무엇이 구현되었나 / 무엇이 *아직* 인가

### ✅ 구현됨

- 잔디 도메인 전체 (ActivityType, ActivityEvent, ActivityDaily, Streak, LevelCalculator)
- DB 2 테이블 + JPA + native upsert (jsonb_set)
- Kafka consumer (`user-activity.v1`, group `insight-api`)
- ingest UseCase (멱등 INSERT + tz 보정 + daily 증분, 같은 tx)
- 조회 UseCase (days fill, level + streak + best + thresholds — visibility 체크는 v0.2 에서 제거)
- iam-api 신규 `/internal/users/{id}/preferences` + `IamUserTimezoneAdapter` (60s 캐시)
- gateway routing + audience + docs proxy
- SecurityConfig + internal-auth issuer/verifier (RSA 2048)
- application.yml + local + test (H2)
- docker-compose (postgres only)
- 빌드 통과 (5 서비스 모두)

### ⚠ 미구현 / 다음 단계

| 영역 | 비고 |
|---|---|
| **출처 서비스 publisher** | core-api / iam-api 에 `ActivityPublisher` (Kafka producer) 추가. `KafkaProducerConfig` (Boot 4 수동 빈) + 활동 발생 8 지점에 `runCatching { publisher.publish(...) }` swallow 호출. 이번 PR 범위 밖 |
| **streak 전용 endpoint** | 현재는 잔디 응답의 `streak` 필드로 함께. 별도 호출이 필요해지면 추가 |
| **raw 13개월 보존 cleanup** | 정책만 있음. cron / scheduled job 미구현 |
| **DLQ / 재시도** | 현재 consumer 는 `auto-commit=true` + 예외 swallow. broker 가 retain 한 메시지는 다음 catch-up 으로 따라잡지만, *처리 중 실패한 메시지는 손실*. 향후 DLQ 추가 |
| **가중치 정책 회귀 배치** | raw 의 score 컬럼은 publish 시점 값으로 보존. 정책 변경 시 daily truncate-and-rebuild 가 *가능* 하지만 배치 코드 없음 |
| **achievements / 배지** | 100일 streak, 첫 솔루션 등 — 별도 BC 가 적절. 미착수 |
| **leaderboard / 친구 비교** | 권한 모델 정리 후 |
| **playground 실데이터 wiring** | 현재 `activity-grass.html` 은 mock 만. `fetch('/api/v1/users/me/activity-grass')` 로 교체 필요 |
| **timezone 변경 시 daily 재계산** | 새 활동만 새 tz, 과거는 그대로. 사용자 요청 시 재계산 endpoint 가 필요 |

---

## 11. 참고 파일 인덱스

| 파일 | 역할 |
|---|---|
| `insight-api/src/.../activity/domain/ActivityType.kt` | 활동 enum 8종 |
| `insight-api/src/.../activity/domain/ActivityEvent.kt` | raw aggregate (멱등 키 포함) |
| `insight-api/src/.../activity/domain/ActivityDaily.kt` | 일별 집계 aggregate |
| `insight-api/src/.../activity/domain/Streak.kt` | 연속 일수 계산 (오늘 기준 거꾸로) |
| `insight-api/src/.../activity/domain/LevelCalculator.kt` | 5단계 색 결정 (relative/absolute) |
| `insight-api/src/.../activity/application/usecase/IngestActivityEventUseCase.kt` | Kafka consumer 가 호출 |
| `insight-api/src/.../activity/application/usecase/GetActivityGrassUseCase.kt` | 잔디 조회 |
| `insight-api/src/.../activity/application/port/*` | 3 port (Event/Daily/Timezone) |
| `insight-api/src/.../activity/config/ActivityWeightProperties.kt` | yml weights 주입 |
| `insight-api/src/.../activity/infrastructure/*Entity.kt`, `*JpaRepository.kt`, `*Adapter.kt` | 3 aggregate JPA + adapter |
| `insight-api/src/.../activity/infrastructure/kafka/KafkaConsumerConfig.kt` | Boot 4 수동 빈 (`@EnableKafka`) |
| `insight-api/src/.../activity/infrastructure/kafka/ActivityEventConsumer.kt` | `@KafkaListener("user-activity.v1")` |
| `insight-api/src/.../activity/infrastructure/iam/IamApiRestClientConfig.kt` | iam-api 호출 RestClient (sub=system) |
| `insight-api/src/.../activity/infrastructure/iam/IamUserTimezoneAdapter.kt` | timezone 조회 + 60s 캐시 + UTC fallback |
| `insight-api/src/.../activity/presentation/ActivityGrassController.kt` | 잔디 조회 endpoints |
| `insight-api/src/.../shared/config/SecurityConfig.kt` | internal JWT verifier |
| `insight-api/src/.../shared/config/IamApiProperties.kt` | `insight.iam-api.base-url` |
| `insight-api/src/.../shared/config/ConfigRegistration.kt` | `@ConfigurationPropertiesScan` |
| `iam-api/src/.../auth/presentation/web/InternalUserPreferencesController.kt` | `/internal/users/{id}/preferences` |
| `gateway/src/.../config/RouteConfig.kt` | `insightApiRoute` + `insightDocsRoute` |
| `gateway/src/.../filter/HeaderInjectionFilter.kt` | audience 분기 `/activity-grass → insight-api` |
| `playground/activity-grass.html` | 디자인 검증 mock (Debug Pulse) |
| `insight-api/docs/activity-grass-design.md` | 설계 사상 / 기획 |

---

## 12. 다음 단계 — 우선순위 제안

1. **출처 서비스 publisher 8 지점** (core-api 7, iam-api 1) — 잔디가 *실제로* 채워지기 시작
2. **playground 의 실데이터 wiring** — mock 분기 제거하고 `fetch()` 호출
3. **bootRun 통합 검증** — kafka-console-producer 로 수동 메시지 → DB → API 라운드트립
4. **DLQ + retry** — 실패 메시지 격리
5. **raw 13개월 보존 cron** — scheduled job 추가
6. **streak 전용 endpoint** — 잔디 카드 + 다른 페이지 (홈/프로필 헤더) 에서 가벼운 조회 필요해지면

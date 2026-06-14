# noti-api · Notifications Overview

> 멘션 알림을 *in-app inbox · email · push* 세 채널로 fan-out 하는 마이크로서비스.
> core-api(producer) → Kafka → noti-api(consumer) → 세 채널 동시 발송.
> 본 문서는 BC 전체 구조 + 흐름 + 외부 연동 + 운영 토글을 한 페이지에 모은 것.

---

## 1. 큰 그림

```
 ┌──────────────────────┐          ┌──────────────────────┐
 │ FE (playground/      │          │ FE (playground/      │
 │   comment-tester)    │          │   mention-inbox)     │
 └──────────┬───────────┘          └──────────┬───────────┘
            │ Bearer (사용자 JWT)              │ Bearer (사용자 JWT)
            ▼                                  ▼
 ┌──────────────────────────────────────────────────────────┐
 │   gateway:8000  ─  Authorization → X-Internal-Auth 발급  │
 │                    (iss=gateway, aud=*-api)              │
 └──────────┬─────────────────────────────┬─────────────────┘
            │                             │
            ▼                             ▼
 ┌────────────────────┐         ┌────────────────────────────┐
 │   core-api:8081    │         │       noti-api:8082        │
 │                    │         │                            │
 │  CreateComment →   │  Kafka  │  MentionEventConsumer →    │
 │  멘션 파싱 →       │ ──────▶ │  ReceiveMentionEventUseCase│
 │  KafkaPublisher    │ topic   │     │                      │
 │     (recipient별)  │ mentions│     ├─ in-app row 적재    │
 └─────────┬──────────┘         │     ├─ EmailSenderPort     │
           │                    │     └─ PushSenderPort      │
           │ resolve userId     │            │   │           │
           ▼                    │            ▼   ▼           │
 ┌────────────────────┐         │       SMTP   FCM           │
 │   iam-api:8080     │         │      (mail-  (HTTP v1)     │
 │  /internal/users/  │◀────────┼──────  hog)                │
 │    {id}/contact    │ noti-api│  (email/displayName 조회)  │
 └────────────────────┘ → iam   └────────────────────────────┘
```

---

## 2. 도메인 모델

위치: `org.studieojavry.notiapi.notification.domain`

| Aggregate | 책임 | DB 테이블 |
|---|---|---|
| `Notification` | 사용자별 알림 row — type, actor, payload(JSON), readAt | `noti_notification` |
| `NotificationSettings` | 사용자별 채널×카테고리 매트릭스 + master switch | `noti_notification_settings` |
| `DeviceToken` | FCM 발송 대상 — 사용자×기기 (멱등 등록) | `noti_device_token` |

### Notification

- `id, recipientUserId, type, actorUserId, payload(jsonb), readAt, createdAt`
- `NotificationType.MENTION_IN_COMMENT` (현재). 향후 `REPLY_TO_MY_COMMENT / NEW_FOLLOWER / WORKSPACE_INVITATION / SECURITY` 확장 예정
- 인덱스: `(recipient_user_id, created_at DESC)` + 부분 인덱스 *unread 만* (`WHERE read_at IS NULL`)

### NotificationSettings

```
masterEnabled : Boolean
email         : { mentions, replies, newFollowers, workspaceInvitations, weeklyDigest, productAnnouncements }
inApp         : { mentions, replies, newFollowers, workspaceInvitations }
```
- jsonb 한 컬럼에 통째 저장 — 새 카테고리는 default + Patch + Response DTO 3곳 동시 갱신
- security alerts 는 *항상 강제 on*, 본 객체엔 보관 X → DTO 가 강제로 채움

### DeviceToken

- `(user_id, token)` unique — 멱등 등록
- `Platform = IOS | ANDROID | WEB`
- 로그아웃/앱 삭제/토큰 무효화 시 DELETE

---

## 3. API 엔드포인트

### 3-1. 외부 (gateway 경유, aud=noti-api)

| Method | Path | 책임 |
|---|---|---|
| GET   | `/api/v1/users/me/notifications` | 내 알림 inbox (limit/offset, unreadOnly 옵션) |
| GET   | `/api/v1/users/me/notifications/unread-count` | unread 카운트 (badge 용) |
| POST  | `/api/v1/users/me/notifications/{id}/read` | 단건 읽음 처리 |
| POST  | `/api/v1/users/me/notifications/read-all` | 전체 읽음 |
| GET   | `/api/v1/users/me/notification-settings` | 내 설정 조회 |
| PATCH | `/api/v1/users/me/notification-settings` | 부분 갱신 (null 미포함 deep merge) |
| POST  | `/api/v1/users/me/device-tokens` | FCM 토큰 등록 (멱등, 201) |
| DELETE| `/api/v1/users/me/device-tokens/{token}` | 토큰 제거 (204, 없어도 OK) |

gateway 의 `RouteConfig` 에서 `notiApiRoute` 가 위 4 path 매칭, `HeaderInjectionFilter` 가 audience=noti-api 토큰 발급.

### 3-2. Internal (gateway 미라우팅)

| Method | Path | 호출자 | 책임 |
|---|---|---|---|
| POST | `/internal/notifications/mentions` | core-api (HTTP fallback 모드) | 멘션 이벤트 동기 적재 |

> Kafka 모드(default)에선 위 endpoint 가 안 쓰임. `noti.publisher.mode=http` 일 때만 활성 — broker 장애 대비 회귀 경로.

---

## 4. 이벤트 흐름 (멘션 알림)

### 4-1. core-api 측

```
POST /api/v1/error-cases/{caseId}/comments
 └─ CreateCommentUseCase
     ├─ MENTION_PATTERN = (?:^|\s)@([A-Za-z0-9_.가-힣-]+)
     │     └─ "@ 앞이 line-start 또는 공백" 일 때만 매칭
     │        (이메일/inline-code 안의 @ 보호)
     ├─ identifier → iamUserQuery.resolveByDisplayName(name) → userId
     ├─ CommentMention(commentId, identifier, mentionedUserId) 저장
     └─ NotificationPublisherPort.publishMentions(event)
          ├─ KafkaNotificationPublisherAdapter (mode=kafka, default)
          │   └─ recipient 별로 1 메시지 publish (fan-out at producer)
          │       topic: notification-events.mentions.v1
          │       key  : recipientUserId (파티션 분산)
          │       value: {recipientUserId, actorUserId, errorCaseId, commentId, snippet}
          └─ NotiApiNotificationPublisherAdapter (mode=http, fallback)
              └─ POST /internal/notifications/mentions (CircuitBreaker `notiApi`)
```

발사 실패는 *swallow* — 알림 누락이 댓글 작성을 막아선 안 됨. mention row 는 DB 에 있어 추후 outbox/재처리 가능.

### 4-2. noti-api 측

```
MentionEventConsumer (@KafkaListener notification-events.mentions.v1, group=noti-api)
 └─ ReceiveMentionEventUseCase
     │  대상 = event.recipientUserIds.distinct(), 각각 NotificationSettings 조회 (없으면 default)
     ├─ masterEnabled=false → 모든 채널 skip
     ├─ in-app   : masterEnabled && inApp.mentions  → InAppMentionWriter (트랜잭션)
     │             └─ noti_notification INSERT
     ├─ email    : masterEnabled && email.mentions  → IamUserContactPort.fetch(userId)
     │             └─ active && email != null → EmailSenderPort.send(...)
     └─ push     : masterEnabled && inApp.mentions  → DeviceTokenRepositoryPort.listByUserId
                   └─ tokens 있음 → PushSenderPort.send(...)
```

- `InAppMentionWriter` 가 *별도 @Service* — Spring self-invocation 우회 (트랜잭션은 in-app 적재만 감쌈)
- email/push 외부 호출은 트랜잭션 *밖* — DB 락을 잡고 외부 요청을 기다리면 안 됨
- 각 채널 실패는 *log + swallow* — 한 채널 장애가 다른 채널을 막지 않음

---

## 5. 발송 채널 추상화 (Ports & Adapters)

### EmailSenderPort

| Adapter | 활성 조건 | 동작 |
|---|---|---|
| `LoggingEmailSenderAdapter` | `noti.email.provider=logging` *또는 미설정* (default) | 콘솔만 |
| `SmtpEmailSenderAdapter` | `noti.email.provider=smtp` | JavaMailSender + MimeMessage (UTF-8, html+text) |

local 에선 mailhog(`noti-mailhog`, SMTP 1026 / UI 8026) 로 검증.

### PushSenderPort

| Adapter | 활성 조건 | 동작 |
|---|---|---|
| `LoggingPushSenderAdapter` | `noti.push.provider=logging` *또는 미설정* (default) | 콘솔만 |
| `FcmPushSenderAdapter` | `noti.push.provider=fcm` *및* `noti.push.fcm.project-id` + `service-account-path` 모두 채워짐 | Firebase HTTP v1 (OAuth2 토큰 + send) — **현재 stub**, 자격증명 없으면 안전 fail |

> default 가 logging 인 이유 — local 첫 기동 시 외부 서비스 없이도 안전하게 동작.

### NotificationPublisherPort (core-api 측)

| Adapter | 활성 조건 | 동작 |
|---|---|---|
| `KafkaNotificationPublisherAdapter` | `noti.publisher.mode=kafka` (default in local) | topic publish |
| `NotiApiNotificationPublisherAdapter` | `noti.publisher.mode=http` (default in base) | RestClient POST `/internal/notifications/mentions` (+ CircuitBreaker) |

---

## 6. 보안 (internal JWT)

모든 서비스간 호출은 `X-Internal-Auth` 헤더의 단명 JWT(RS256, 기본 60s).

| 서비스 | issuer | verifier (known issuers) |
|---|---|---|
| gateway | gateway → 모든 BE | — |
| core-api | core-api → iam-api / noti-api | gateway |
| iam-api  | — | gateway, core-api, **noti-api** |
| noti-api | **noti-api → iam-api** (contact 조회) | gateway, core-api |

> noti-api 가 issuer 역할도 하게 된 건 멘션 fan-out 시 iam-api 로 *recipient 의 email/displayName* 을 조회해야 하기 때문. RSA 2048 키 `noti-api-local-1` 사용 (`application-local.yml`).

### IamUserContactAdapter

- `GET /internal/users/{userId}/contact` 호출 (Kafka consumer 안의 비-요청 컨텍스트라 sub=`system`)
- 응답: `{userId, email, displayName, status, active}`
- 404/타임아웃/그 외 실패는 *null 반환* → email/push 채널 skip (graceful)

---

## 7. 설정 (yml + ENV)

### noti-api `application.yml` (base)

```yaml
noti:
  email:
    provider: ${NOTI_EMAIL_PROVIDER:smtp}     # smtp | logging
    from-address: ${NOTI_EMAIL_FROM:notifications@error-archive.local}
    from-name: ${NOTI_EMAIL_FROM_NAME:Error Archive}
    subject-prefix: "[Error Archive]"
  push:
    provider: ${NOTI_PUSH_PROVIDER:logging}   # fcm | logging
    fcm:
      project-id: ${NOTI_FCM_PROJECT_ID:}
      service-account-path: ${NOTI_FCM_SERVICE_ACCOUNT_PATH:}
```

### noti-api `application-local.yml`

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9094}
    consumer:
      group-id: noti-api
      auto-offset-reset: earliest
  mail:
    host: ${NOTI_MAIL_HOST:localhost}
    port: ${NOTI_MAIL_PORT:1026}             # mailhog 충돌 회피 (iam-mailhog=1025)
internal-auth:
  issuer:                                     # noti-api → iam-api 호출용
    name: noti-api
    key-id: noti-api-local-1
    private-key-pem: <RSA 2048 PKCS8>
  verifier:
    audience: noti-api
    known-issuers: { gateway, core-api }
```

### core-api `application-local.yml`

```yaml
noti:
  publisher:
    mode: ${NOTI_PUBLISHER_MODE:kafka}        # kafka (default) | http
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9094}
```

> base `application.yml` 의 default 는 `http` — test 등 외부 환경에선 broker 없이도 컨텍스트 로드 가능.

### Spring Boot 4 의 Kafka 빈 수동 등록

Spring Boot 4 가 KafkaAutoConfiguration 을 별도 starter 로 분리했고 현재 의존성에 미포함 → 두 곳에 수동 `@Configuration` 으로 빈 정의:

- `core-api/.../noti/KafkaProducerConfig.kt` — `DefaultKafkaProducerFactory` + `KafkaTemplate<String,String>` (`@ConditionalOnProperty mode=kafka`)
- `noti-api/.../kafka/KafkaConsumerConfig.kt` — `@EnableKafka` + `DefaultKafkaConsumerFactory` + `kafkaListenerContainerFactory` (이름 고정)

---

## 8. 인프라 (`noti-api/docker-compose.yml`)

| 컨테이너 | 포트 | 역할 |
|---|---|---|
| `noti-postgres` (postgres:16-alpine) | 5434→5432 | `noti_db` |
| `noti-kafka` (apache/kafka:3.8.0, KRaft) | 9094 (외부), 9092 (내부) | broker. PLAINTEXT advertised = `kafka:9092` (docker net), EXTERNAL = `localhost:9094` (호스트) |
| `noti-kafka-ui` (provectuslabs/kafka-ui:v0.7.2) | 8027 | 토픽/메시지 브라우저 + consumer lag |
| `noti-mailhog` (mailhog v1.0.1) | 1026 (SMTP), 8026 (UI) | local email sink |

---

## 9. e2e 검증 흐름

1. `docker compose -f noti-api/docker-compose.yml up -d` — Kafka + mailhog + kafka-ui 기동
2. 4 서비스 기동 — gateway / iam-api / core-api / noti-api
3. `playground/comment-tester.html` (gateway 8000 절대 URL) 에서 user A JWT 로 case ID 입력 → `@user-B` 멘션 댓글 작성
4. 검증 포인트:
   - **kafka-ui (http://localhost:8027)** → `notification-events.mentions.v1` 토픽 → 메시지 등장 + key=recipient userId
   - **mention-inbox (`playground/mention-inbox.html`)** 에서 user B JWT 로 refresh → 알림 도착, unread badge +1
   - **mailhog UI (http://localhost:8026)** → 메일 도착 (settings.email.mentions=true 인 경우)
   - **noti-api 로그** → `[mention-email]` / `[mention-push]` debug 라인
   - **DB** → `noti_notification` 새 row + `noti_device_token` 등록된 토큰

---

## 10. 의도된 미구현 / TODO

| 영역 | 상태 | 비고 |
|---|---|---|
| FCM HTTP v1 OAuth2 + send | **stub** | `FcmPushSenderAdapter` — 자격증명 미설정 시 안전 fail. 실 운영 전 채워야 함 |
| Notification 타입 확장 | enum 1종 (`MENTION_IN_COMMENT`) | `REPLY_TO_MY_COMMENT`, `NEW_FOLLOWER`, `WORKSPACE_INVITATION`, `SECURITY` 추가 예정 |
| outbox / DLQ | 없음 | 현재 Kafka 발사 실패는 swallow. 추후 outbox 테이블 + retry/DLQ |
| weekly digest | 설정 토글만 있음 | scheduled job 미구현 |
| email i18n | 영문 하드코딩 | 향후 사용자 locale 별 템플릿 |
| device token TTL/회전 | 없음 | FCM 토큰 만료 처리 정책 필요 |
| consumer at-least-once 보장 | `enable-auto-commit=true` | 처리 중 예외는 재시도되지만 commit 타이밍에 따라 중복 가능. DB 측 멱등성으로 흡수 |

---

## 11. 참고 파일

| 파일 | 역할 |
|---|---|
| `noti-api/src/.../notification/domain/Notification.kt` | aggregate root |
| `noti-api/src/.../notification/domain/NotificationSettings.kt` | settings 매트릭스 + Patch deep merge |
| `noti-api/src/.../notification/domain/DeviceToken.kt` | FCM 등록 aggregate |
| `noti-api/src/.../notification/application/NotificationUseCases.kt` | `ReceiveMentionEventUseCase` 의 3채널 fan-out + Inbox/MarkRead use cases |
| `noti-api/src/.../notification/application/sender/EmailSenderPort.kt` | email 추상화 |
| `noti-api/src/.../notification/application/sender/PushSenderPort.kt` | push 추상화 |
| `noti-api/src/.../notification/application/IamUserContactPort.kt` | iam-api 로 contact 조회 |
| `noti-api/src/.../notification/infrastructure/kafka/MentionEventConsumer.kt` | Kafka listener |
| `noti-api/src/.../notification/infrastructure/kafka/KafkaConsumerConfig.kt` | ConsumerFactory + ListenerContainerFactory 수동 등록 |
| `noti-api/src/.../notification/infrastructure/sender/Smtp/Fcm/LoggingEmail/PushSenderAdapter.kt` | 4 어댑터 |
| `noti-api/src/.../notification/presentation/NotificationController.kt` | inbox + read endpoints |
| `noti-api/src/.../notification/presentation/InternalNotificationController.kt` | http fallback 모드의 동기 적재 endpoint |
| `noti-api/src/.../notification/presentation/DeviceTokenController.kt` | device tokens endpoints |
| `core-api/src/.../errorcase/shared/infrastructure/noti/KafkaNotificationPublisherAdapter.kt` | Kafka producer |
| `core-api/src/.../errorcase/shared/infrastructure/noti/KafkaProducerConfig.kt` | ProducerFactory + KafkaTemplate 수동 등록 |
| `iam-api/src/.../auth/presentation/web/InternalUserContactController.kt` | `/internal/users/{id}/contact` |
| `gateway/src/.../config/RouteConfig.kt` | noti-api 라우팅 (notifications + notification-settings + device-tokens) |
| `gateway/src/.../filter/HeaderInjectionFilter.kt` | 위 path → audience=noti-api 분기 |

# error-archive Architecture Recommendations

`architecture-overview.md`의 다이어그램에 반영된 권장사항의 **근거와 패턴 적용 가이드**를 모은 문서.

대상 독자: 새 BC를 추가하거나 기존 서비스를 분리/통합할 때, 아래 원칙을 기준으로 의사결정하기 위함.

---

## 0. 한 줄 정리

- **Core / Supporting / Process & Read** 도메인 구분을 유지한다.
- **이벤트는 비싸다 — 컨슈머가 있을 때만 발행한다.**
- **동기 RPC는 진짜 응답이 즉시 필요할 때만**, 그 외엔 이벤트.
- **AuthZ는 JWT 클레임에 미리 담아 hot path에서 동기 호출하지 않는다.**
- **DB-per-service**, 다른 서비스 DB 직접 접근 금지.

---

## 1. Strong Recommendations (다이어그램에 반영됨)

### 1.1 Timeline을 case-service 안의 BC로 통합

**문제**: 처음 다이어그램은 timeline-service를 별도로 분리.

**근거**:
- timeline 없는 case는 의미 있어도, **case 없는 timeline은 무의미**. 분리할 도메인적 이유가 약함.
- step 추가는 case 상태 변경과 같은 트랜잭션이어야 자연스러움 (예: "마지막 step이 RESOLVED면 case도 RESOLVED").
- 트래픽 패턴이 동일 (case를 보는 사용자가 timeline도 본다).
- 분리해서 얻는 이점이 작음 — 독립 배포의 가치가 거의 없음.

**적용**:
- deployment 단위는 `case-service` 하나.
- 패키지/모듈 수준에서 `case` BC와 `timeline` BC로 분리. 도메인 모델 import 금지, 협력은 in-process port.
- "BC 분리 ≠ 서비스 분리"의 좋은 예.

**참고**: iam-api에서 `auth` / `social` / `workspace`를 같은 서비스 안에 둔 것과 동일한 결정.

---

### 1.2 Solution이 Timeline에 의존하지 않게

**문제**: 처음 다이어그램은 `SOL → TL (Validate refs)` 동기 호출.

**근거**:
- Solution은 **재사용 가능한 해결 템플릿**이어야 한다("이런 에러엔 이렇게 해결한다").
- 특정 timeline step에 묶이면 재사용성 훼손 + timeline 변경에 끌려감.

**적용**:
- Solution은 **Case 또는 fingerprint**에 붙는다(timeline step이 아닌).
- 정말 timeline step과의 연관 정보가 필요하면, Solution 도메인에 step의 **사실 사본**(text/timestamp)만 보존. 양방향 참조 X.

---

### 1.3 AuthZ는 JWT 클레임 + 이벤트 동기화 (동기 RPC 회피)

**문제**: 처음 다이어그램은 `CASE → WS (AuthZ check)`, `TL → CASE (AuthZ)` 매 요청 동기 호출.

**근거**:
- hot path에서 다운스트림 의존은 **병목**. WS 다운 = CASE 다운.
- 권한 정보는 자주 변하지 않음 → JWT에 미리 담아도 충분.

**적용**:
- **identity-service의 access JWT 발급 시** 사용자의 워크스페이스 권한을 클레임에 포함:
  ```json
  {
    "sub": "<userId>",
    "exp": ...,
    "ws": [
      { "id": "<workspaceId>", "role": "ADMIN" },
      { "id": "<workspaceId>", "role": "WRITE" }
    ]
  }
  ```
- **case-service**는 JWT만 보고 판정. 외부 호출 없음.
- **권한 변경 즉시 반영**: workspace-service가 `MemberRoleChanged` 발행 → identity-service 구독 → 해당 사용자의 refresh family revoke → 다음 요청에서 새 토큰 발급(갱신된 권한).
- **불가피한 동기 RPC**(예: 워크스페이스 영구 삭제 직전 검증)는 점선으로 표시. cold path만.

**Trade-off**:
- access TTL(15분) 동안 권한 변경이 늦게 반영. 보통 허용 가능.
- 즉시 반영이 필요하면 family revoke가 그 역할 — 다음 요청부터 효과.

---

### 1.4 각 서비스의 DB는 분리 (Database-per-service)

**근거**: MSA의 핵심 원칙. 한 서비스의 스키마 변경이 다른 서비스를 깨뜨리지 않게.

**적용**:
- 다른 서비스의 DB에 직접 SQL을 쏘지 않는다.
- 다른 서비스의 데이터가 필요하면 **그 서비스의 API**(동기) 또는 **그 서비스가 발행한 이벤트의 사본**(비동기).
- 단일 모놀리스 시점에는 같은 DB여도, 스키마 prefix(`iam_*`, `case_*`, ...)와 무결성 제약 분리로 분리 가능성을 유지.

**현재 상황**: iam-api는 한 DB 안에 `iam_user`, `iam_workspace`, `iam_follow` 등을 나누고 있음. 분리 시 그대로 떼낼 수 있는 형태.

---

### 1.5 외부 의존성 명시

**근거**: 외부 SaaS 의존도 아키텍처의 일부. 장애 영향도 평가에 필수.

**적용**: 다이어그램에 점선으로 표시.

| 의존 | 영향 | 장애 시 |
|---|---|---|
| GitHub OAuth | identity-service 신규 로그인 | 신규 로그인 불가, 기존 토큰은 정상 동작 |
| FCM/APNs | notification-service 푸시 | 인박스 갱신은 정상, 푸시만 지연 |
| Email Provider | notification, workspace 초대 | 메일 발송 지연. 큐에 적재 후 재시도. |
| Object Storage | case 첨부, publishing 스냅샷 | 업로드/조회 실패. 콘텐츠 도메인의 read는 캐시로 완화. |

---

### 1.6 API Gateway의 책임 명시

**근거**: gateway가 횡단 관심사를 처리해야 각 서비스가 비즈니스 로직에 집중 가능.

**적용**:
- **JWT 검증**: 각 서비스마다 다시 검증하지 않게. 단, 백엔드 내부에서도 토큰 무결성 재확인은 권장.
- **Rate limiting**: per-IP, per-user.
- **라우팅**: path 기반 (`/api/v1/users/**` → identity 또는 profile 등).
- **CORS**: 화이트리스트.
- **응답 캐싱**: read-heavy 엔드포인트(예: 공개된 published case).

---

### 1.7 publishing-service 내부 BC 분리

**근거**: `PublishedSnapshot`과 `ShareLink`는 의미·정책이 다름.

| BC | 책임 | 일관성 요구 |
|---|---|---|
| **snapshot** | case의 공개 시점 콘텐츠를 **불변 사본**으로 저장 | 한 번 발행되면 변경 불가. 콘텐츠 무결성. |
| **sharelink** | snapshot을 가리키는 URL/토큰. 만료, 접근 제어. | 토큰 회수 가능. 정책 변경 빈번. |

**적용**: 같은 deployment, BC만 분리. snapshot 도메인은 sharelink를 모르고, sharelink는 snapshot의 ID만 참조.

---

## 2. Patterns to Apply (각 서비스에 공통 적용)

### 2.1 Outbox 패턴 (이벤트 발행)

**문제**: DB INSERT와 이벤트 발행이 다른 시스템(broker)이라 atomic하지 않음.

```
[안 됨]
  INSERT case
  publish CaseCreated   ← INSERT는 됐는데 publish가 실패하면 이벤트 유실
  COMMIT
```

**해결**:
```
[Outbox]
  BEGIN
  INSERT case
  INSERT outbox(event_payload)   ← 같은 트랜잭션
  COMMIT
  
[별도 publisher]
  outbox 폴링 → broker로 발행 → outbox 마킹
```

**구현 가이드**:
- 모든 이벤트 발행 서비스에 `outbox` 테이블.
- `DomainEventPublisherPort`의 어댑터가 outbox INSERT만.
- 별도 worker(또는 Debezium 같은 CDC)가 broker로 송신.

---

### 2.2 Idempotency Key (이벤트 컨슈머)

**문제**: at-least-once delivery가 일반적. 중복 메시지 처리.

**적용**:
- 모든 이벤트의 `eventId`를 처리 후 `processed_event` 테이블에 INSERT (UNIQUE on eventId).
- INSERT 충돌 시 무시.
- 또는 자연 키 기반 멱등성(`(memberId, joinedAt)` 같은) 활용.

---

### 2.3 Circuit Breaker (동기 RPC 호출자)

**문제**: 다운스트림 장애 시 호출자 스레드 풀 고갈 → 연쇄 장애.

**적용**:
- Resilience4j / Spring Cloud Circuit Breaker.
- timeout, retry, fallback 정의.
- cold path 동기 호출(예: workspace 삭제 직전 보호 검증)에 필수.

---

### 2.4 Health Check / Readiness Probe

- `/actuator/health/liveness`: 프로세스 alive.
- `/actuator/health/readiness`: 트래픽 받을 준비 (DB · broker · 외부 의존 검사).
- K8s probe와 연결.

---

### 2.5 Distributed Tracing (OpenTelemetry)

**적용**:
- 모든 서비스가 OTel 에이전트/SDK 내장.
- HTTP 요청에 `traceparent` 헤더 자동 전파.
- **이벤트 메시지에도 traceId 포함**: 발행 시 `header.traceparent`, 컨슈머가 이를 이어받음.
- Jaeger / Tempo / Datadog APM 등으로 시각화.

---

### 2.6 Schema Registry (이벤트 스키마 관리)

**문제**: 이벤트 페이로드 변경 시 컨슈머와 호환성 문제.

**적용**:
- Confluent Schema Registry / Apicurio.
- Avro/Protobuf 스키마 버전 관리.
- backward compatibility 강제.
- **신규 필드 추가는 OK, 기존 필드 제거는 금지**(나중 버전에서 deprecated 표시 후 제거).
- 이벤트 이름에 버전 포함 가능: `MemberJoined.v1` → `MemberJoined.v2`.

---

## 3. 같은 서비스 안의 BC 협력 규칙

### 3.1 절대 금지
- BC A의 도메인 모델이 BC B의 도메인 모델 import.
- BC A의 use case가 BC B의 repository 직접 호출.
- BC A와 B가 같은 테이블을 동시에 매핑.

### 3.2 허용
- BC A의 인프라 어댑터가 BC B의 read 리포지토리 사용 (한 방향만).
- 두 BC가 `shared/` 안의 작은 공통 모델(이벤트 인터페이스, 식별자 타입) 공유.
- 한 트랜잭션 안에 두 BC의 변경이 들어가는 것 (단, eventual consistency로 대체 가능하면 그쪽 선호).

### 3.3 권장 패턴: Anti-Corruption Layer (ACL)
하류 BC가 상류 BC의 모델을 자기 언어로 번역하는 어댑터.

**현재 사례**:
- `workspace.application.port.MemberSummaryReaderPort` — auth.User를 `MemberSummary`로 번역.
- `social.application.port.UserSummaryReaderPort` — 동일 패턴.

이 패턴이 **"같은 서비스 안에서도 BC를 모르게 하는"** 정확한 기법.

---

## 4. 외부 식별자(Public ID) 정책

### 4.1 원칙
- **내부 PK** (`Long id`): 같은 서비스 안의 FK·인덱스 효율을 위해 사용.
- **외부 식별자** (`UUID publicId`): URL, API 응답, 이벤트 페이로드, 다른 서비스 참조에 사용.

### 4.2 적용 우선순위
| 도메인 | publicId 필요성 | 적용 시점 |
|---|---|---|
| User | 높음 (FE의 `/users/{id}`, 다른 서비스의 user 참조) | 우선 |
| Workspace | 높음 (FE의 `/workspaces/{id}`, case에서 참조) | 우선 |
| Case | 높음 (publishing 스냅샷 URL의 안정성) | core-api 도입 시 |
| Notification | 낮음 (인박스 내부 ID로 충분) | 필요 시 |
| Follow row | 불필요 (관계 row, 외부 참조 없음) | — |

### 4.3 PK 노출이 위험한 이유
- enumerable: `/workspaces/1, /workspaces/2` → 워크스페이스 개수 추정 가능.
- 서비스 분리 시 PK 충돌 가능성 (각 서비스 IDENTITY가 1부터 시작).
- 데이터 마이그레이션 시 충돌.

---

## 5. 의사결정 체크리스트

### 5.1 새 BC를 만들 때
- [ ] 유비쿼터스 언어가 다른가? (모델 의미가 충분히 달라서 한 모델로 표현하면 어색한가)
- [ ] 일관성 요구가 다른가?
- [ ] 변경 빈도/팀이 다른가?
- 모두 아니면 → 기존 BC 안에 두기.

### 5.2 새 서비스로 분리할 때
- [ ] 독립 배포의 가치가 있는가? (장애 격리, 스케일 단위, 팀 분리)
- [ ] 같은 트랜잭션을 자주 요구하지 않는가?
- [ ] 이벤트 + eventual consistency로 충분한가?
- 모두 yes → 분리. 아니면 같은 서비스 안 BC.

### 5.3 새 협력을 추가할 때
- [ ] 응답을 즉시 받아야 하는가?
  - yes → 동기 RPC. circuit breaker 필수.
  - no → 다음 질문.
- [ ] 컨슈머가 1+ 존재하는가?
  - yes → 이벤트.
  - no → **아무것도 만들지 마라.** YAGNI.

### 5.4 새 이벤트를 정의할 때
- [ ] 페이로드는 불변 데이터 클래스인가?
- [ ] 외부 식별자(publicId)를 사용하는가?
- [ ] schema registry에 등록되었는가?
- [ ] 컨슈머가 멱등 처리하는가?
- [ ] traceId가 전파되는가?

---

## 6. 안티 패턴 (피해야 할 것들)

### 6.1 "혹시 모르니 이벤트 미리 깔아두기"
컨슈머 없는 이벤트는 죽은 코드. 발행자 변경 시 부담만 증가.

### 6.2 "Shared Kernel을 점점 키우기"
공통이라고 다 빼면 결국 모놀리스. **진짜 핵심 공통 어휘**(이벤트 인터페이스, 식별자 타입)에만 한정.

### 6.3 "양쪽이 서로의 인프라를 import"
양방향 의존. 의존 방향 잘못 잡힌 신호 — 도메인 재설계 신호.

### 6.4 "동기 호출 체인이 3 hops 넘음"
`A → B → C → D`. 한 사용자 요청이 여러 서비스를 동기적으로 거치면 latency·장애전파 폭발. 이벤트로 풀거나 BFF/aggregator 패턴 도입.

### 6.5 "ID로 다른 서비스 DB 직접 join"
DB-per-service 위반. 절대 금지.

### 6.6 "권한 정보를 매 요청 동기 조회"
hot path 병목. JWT 클레임 + 이벤트 동기화로.

### 6.7 "도메인 객체를 그대로 이벤트 페이로드에 직렬화"
도메인 변경이 외부 계약 변경으로 누설. 이벤트 페이로드는 별도 DTO.

---

## 7. 우선순위 로드맵

### Phase 1 (현재 — 모놀리스 경계 안의 BC 분리)
- ✅ iam-api 안에 auth/social/workspace BC 분리 (완료)
- ⏳ core-api 안에 case/timeline BC 분리
- ⏳ User/Workspace에 publicId(UUID) 도입
- ⏳ API Gateway 도입 (단일 진입점)

### Phase 2 (이벤트 인프라)
- Outbox 테이블 + worker
- in-process Spring `ApplicationEventPublisher`로 시작 → broker로 점진 이전
- Schema Registry 선정

### Phase 3 (서비스 분리 시작)
- identity-service 먼저 분리 (보안 격리 가치 가장 큼)
- workspace-service, profile-service 분리
- core 도메인은 마지막에 (가장 자주 바뀌므로)

### Phase 4 (Process & Read)
- notification-service 신설
- insight-service 신설 (read-model only)
- publishing-service 신설

각 Phase 진입 조건은 **"분리하지 않으면 진짜 아픈 지점이 있는가"** — 트래픽, 장애 영향, 팀 분리 등.

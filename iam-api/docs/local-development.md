# iam-api Local Development

로컬 개발 환경 셋업 가이드.

---

## 1. 환경별 설정 구조

```
src/main/resources/
├── application.yml              ← 공통 (모든 환경)
├── application-local.yml        ← 로컬 개발 (default profile)
├── application-dev.yml          ← dev 서버
├── application-stg.yml          ← staging
└── application-prod.yml         ← production

src/test/resources/
└── application-test.yml         ← 테스트 (@ActiveProfiles("test"))
```

`application.yml` 의 `spring.profiles.default: local` 설정 덕분에 **명시 안 하면 local 프로파일로 부팅**.

dev/stg/prod 는 `SPRING_PROFILES_ACTIVE` env 로만 활성화:
```bash
SPRING_PROFILES_ACTIVE=prod java -jar iam-api.jar
```

---

## 2. PostgreSQL 띄우기 (로컬)

```bash
cd iam-api
docker compose up -d
```

| 항목 | 값 |
|---|---|
| DB | `iam_db` |
| User | `iam` |
| Password | `iam_local_pw` (dev 한정) |
| Port | `5432` |
| 데이터 볼륨 | `iam-postgres-data` |

상태 확인:
```bash
docker compose ps
docker compose logs -f iam-postgres
```

내려놓기 (데이터 보존):
```bash
docker compose down
```

내려놓고 데이터까지 초기화:
```bash
docker compose down -v
```

---

## 3. iam-api 실행

```bash
./gradlew bootRun
```

`application.yml` 의 `spring.profiles.default: local` 에 의해 자동으로 **local 프로파일**로 부팅 → `application-local.yml` 적용 → docker-compose 의 PostgreSQL 에 접속.

명시적으로 다른 프로파일 사용:
```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

---

## 4. 테스트 실행

```bash
./gradlew test
```

테스트 클래스의 `@ActiveProfiles("test")` 가 `application-test.yml` 을 활성화 → H2 in-memory(PG 호환 모드)로 부팅. **docker 불필요**.

---

## 5. DB 직접 접속 (psql)

```bash
docker compose exec iam-postgres psql -U iam -d iam_db
```

또는 호스트에서:
```bash
psql -h localhost -p 5432 -U iam -d iam_db
# 비밀번호: iam_local_pw
```

스키마 확인:
```sql
\dt              -- 테이블 목록
\d iam_user      -- iam_user 테이블 구조
```

---

## 6. 환경별 차이 요약

| 항목 | local | dev | stg | prod |
|---|---|---|---|---|
| DB host | localhost(docker) | dev-rds | stg-rds | prod-rds |
| DB user/password | yml(placeholder) | env | env(SecretsManager) | env(SecretsManager) |
| `ddl-auto` | `update` | `update` | `validate` | `validate` |
| `show-sql` | true | false | false | false |
| 앱 로그 레벨 | DEBUG | INFO | INFO | INFO |
| root 로그 레벨 | (default) | INFO | WARN | WARN |
| JWT secret | yml(local placeholder) | env | env | env |
| Cookie `secure` | false | true | true | true |
| Cookie `domain` | (none) | `.dev.studieo-javry.com` | `.stg...` | `.studieo-javry.com` |
| Hikari pool max | 5 | 10 | 20 | 30 |
| Actuator 경로 | `/actuator` | `/actuator` | `/actuator` | `/internal/actuator` |
| Health 상세 | default | default | when-authorized | never |
| 에러 메시지 노출 | true | true | true | false |

---

## 7. dev/stg/prod 배포 시 필요한 env

| env | 의미 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` / `stg` / `prod` 중 하나 |
| `IAM_DB_URL` | `jdbc:postgresql://<host>:<port>/<db>` |
| `IAM_DB_USERNAME` | DB 유저 |
| `IAM_DB_PASSWORD` | DB 비밀번호 (Secrets Manager 권장) |
| `IAM_JWT_SECRET` | 64자 이상 무작위 ASCII |
| `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET` | 환경별 별개 OAuth App |

`local` 외 프로파일은 위 env 가 누락되면 부팅 실패(`${ENV}` 에 기본값 없음). 의도된 동작 — 운영에서 placeholder 가 실수로 사용되는 사고 방지.

---

## 8. 운영 도입 시 추가 작업 (현재 미구현)

- **Flyway/Liquibase 마이그레이션 도입** — `ddl-auto: validate` 운영을 위해 필수.
- **Schema Registry / Secrets Manager 연동** — JWT secret 등 secret rotation 자동화.
- **GitHub OAuth App 환경별 분리** — local/dev/stg/prod 각각 별개 OAuth App 등록.
- **CORS 화이트리스트** — `cookie.domain` 과 함께 origin 제한.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew build

# Run
./gradlew bootRun

# Test (all)
./gradlew test

# Test (single class)
./gradlew test --tests "org.studieojavry.coreapi.SomeTest"
```

## Architecture

This is a **Spring Boot 4 / Kotlin** application following **Hexagonal Architecture (Ports & Adapters)** within the `errorcase` bounded context. The BC is internally split into **6 sub-modules** by aggregate (2026-05-29).

```
errorcase/                  ← bounded context root
  case/                     ← ErrorCase aggregate (본문/메타/snapshot/status)
  step/                     ← Step aggregate (해결 시도 타임라인)
  solution/                 ← Solution aggregate (해결방법 묶음)
  snippet/                  ← CodeSnippet aggregate (독립 리소스 → 케이스 link)
  attachment/               ← Attachment aggregate (파일 + storage)
  shared/                   ← cross-aggregate: ErrorCaseAccess, WorkspaceQueryPort,
                              MarkerIdGeneratorPort, Iam adapter, etc.

  <각 sub-module 내부 구조>
    domain/model/           # Aggregate root + VOs (no framework deps)
    application/
      command/              # Input carriers for use cases
      port/                 # Interfaces the application depends on
      usecase/              # @Service application services, one per use case
    presentation/web/       # REST controllers + request/response DTOs
    infrastructure/         # JPA, storage, scheduling, external adapters
    config/                 # @ConfigurationProperties for this aggregate
shared/                     # core-api 루트의 utility (FingerprintGenerator 등)
```

**Flow:** `Controller` → `Command` → `UseCase` → `Port` (interface) → adapter (infrastructure)

**Cross-aggregate references** (예: Step UseCase 가 ErrorCase 권한 검사) 는 **shared** 의 `ErrorCaseAccess` 를 거쳐 한 곳에서 의존성을 흡수한다. BC 자체 분리(Issue/Discovery 등) 시점은 `core-api/docs/revisitable-decisions.md` §재검토 트리거 참조.

## Key Design Decisions

- **Ports are annotated `@Repository`** — even non-JPA ports like `AttachmentStoragePort` and `ErrorSnaphostExtractorPort` use this annotation so Spring can proxy them. Implementations will live in an infrastructure layer.
- **`Fingerprint`** is a SHA-256 hash of `exceptionClass + normalized stack trace`. `RawStackTrace.normalized()` strips line numbers (`:123`) before hashing so fingerprints stay stable across code changes — used for deduplication.
- **`Attachment.embedToken()`** returns `@attach(markerId)` — a marker syntax for embedding attachments inline in description text.
- **`ErrorSnapshot`** is extracted from a raw paste string via `ErrorSnaphostExtractorPort.extract()`, which parses out exception class, message, and stack trace.
- **Use cases expose a nested `Result` data class** as their return type rather than leaking domain models to callers.
- **`ErrorCase` uses a private constructor + companion `create()`** factory — direct instantiation is disallowed.

## Stack

- Kotlin 2.3.20, JVM 25, Spring Boot 4.0.4
- Spring Data JPA, Spring Validation, Spring Web
- Gradle 9.4 with `kotlin-plugin-spring` and `kotlin-plugin-jpa`
- JPA `@Entity` / `@MappedSuperclass` / `@Embeddable` are open-ed via `allopen` plugin

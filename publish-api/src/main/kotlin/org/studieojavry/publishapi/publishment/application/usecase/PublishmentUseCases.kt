package org.studieojavry.publishapi.publishment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.publishapi.publishment.application.port.ActivityEventPublisherPort
import org.studieojavry.publishapi.publishment.application.port.CasePublishmentRepositoryPort
import org.studieojavry.publishapi.publishment.application.port.CoreCaseDataPort
import org.studieojavry.publishapi.publishment.application.port.CoreCaseNotFoundException
import org.studieojavry.publishapi.publishment.application.port.IdempotencyRecordPort
import org.studieojavry.publishapi.publishment.application.port.MyPublishmentQuery
import org.studieojavry.publishapi.publishment.application.port.MyPublishmentSort
import org.studieojavry.publishapi.publishment.application.port.OriginalCaseStatusReaderPort
import org.studieojavry.publishapi.publishment.application.port.OwnerPublishmentSummary
import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.PublishmentStatus
import org.studieojavry.publishapi.publishment.domain.SourceState
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import org.studieojavry.publishapi.publishment.domain.SlugGenerator
import org.studieojavry.publishapi.publishment.domain.Visibility
import org.studieojavry.publishapi.shared.config.SlugProperties
import java.time.Instant

class PublishmentNotFoundException(message: String) : RuntimeException(message)
class PublishmentForbiddenException(message: String) : RuntimeException(message)

/**
 * 발행 콘텐츠 규칙 위반 — 예: 선별된 step 이 0개.
 * 요청 자체는 well-formed 이나 발행 정책을 만족하지 못하는 경우 → 422 로 매핑.
 */
class PublishmentContentException(message: String) : RuntimeException(message)

/**
 * 멱등성 충돌 — 같은 `Idempotency-Key` 로 다른 payload 재사용, 또는 동시 중복 요청 → 409.
 */
class IdempotencyConflictException(message: String) : RuntimeException(message)

/**
 * 원본 case 가 삭제되어 재발행 불가 → 409. (발행물 자체는 유지되나 새 스냅샷을 못 만듦.)
 */
class PublishmentSourceDeletedException(message: String) : RuntimeException(message)

/**
 * 케이스당 1 canonical 발행물 정책 — 이미 발행된 케이스를 (새 요청으로) 다시 발행 → 409.
 * 클라이언트는 새로 만들지 말고 기존 발행물(`existingSlug`)을 재발행/편집해야 한다.
 */
class PublishmentAlreadyExistsException(val existingSlug: String) :
    RuntimeException("이미 발행된 케이스입니다 — 기존 발행물(slug=$existingSlug)을 재발행/편집하세요")

/**
 * 내려진(UNPUBLISHED) 발행물의 공개 라우트 접근 → 410 Gone.
 * row·스냅샷은 살아있으나 작성자가 비공개 처리한 상태. 재공개하면 다시 200.
 */
class PublishmentGoneException(message: String) : RuntimeException(message)

data class PublishInput(
    val caseId: Long,
    val title: String?,                       // null 이면 원본 case title 사용
    val summary: String?,
    val visibility: Visibility = Visibility.PUBLIC,
    val includeStepIds: Set<Long>?,           // null = 전체
    val includeSolutionIds: Set<Long>?,       // null = 전체
    val excludedSnippetMarkerIds: Set<String> = emptySet(),
    val excludedAttachmentMarkerIds: Set<String> = emptySet(),
    val options: PublishOptions = PublishOptions.defaults(),
)

@Service
class CreatePublishmentUseCase(
    private val coreCaseData: CoreCaseDataPort,
    private val repository: CasePublishmentRepositoryPort,
    private val slugProperties: SlugProperties,
    private val activityEventPublisher: ActivityEventPublisherPort,
    private val idempotencyRecord: IdempotencyRecordPort,
) {
    /** 발행 결과 + 멱등 재생 여부. `replayed=true` 면 컨트롤러가 201 대신 200 으로 응답. */
    data class Result(val publishment: CasePublishment, val replayed: Boolean)

    @Transactional
    fun invoke(
        requesterUserId: Long,
        idempotencyKey: String,
        requestHash: String,
        input: PublishInput,
    ): Result {
        // 멱등성 — 같은 (key, user) 재요청이면 최초 발행물 재생.
        idempotencyRecord.find(idempotencyKey, requesterUserId)?.let { existing ->
            if (existing.requestHash != requestHash) {
                throw IdempotencyConflictException(
                    "Idempotency-Key '$idempotencyKey' 가 다른 요청 본문으로 재사용됨",
                )
            }
            val prior = repository.findBySlug(existing.slug)
                ?: throw IdempotencyConflictException("멱등 기록은 있으나 발행물이 없음: ${existing.slug}")
            return Result(publishment = prior, replayed = true)
        }

        // 케이스당 1 canonical 발행물 — 이미 발행된 케이스면 새로 만들지 않고 409(기존 slug 안내).
        // 클라이언트는 재발행(POST …/republish, 현재는 PATCH /by-slug/{slug})/편집으로 유도.
        repository.findByOriginalCaseId(input.caseId)?.let { existing ->
            if (existing.ownerUserId == requesterUserId) {
                throw PublishmentAlreadyExistsException(existing.slug)
            }
            // 다른 소유자(케이스 owner 만 발행 가능하므로 정상 흐름에선 발생 X) → 아래 full-data 가 403 처리.
        }

        val full = coreCaseData.fetchFullData(input.caseId)
            ?: throw CoreCaseNotFoundException("case not found: ${input.caseId}")

        val snapshot = ContentSnapshotBuilder.build(
            full = full,
            includeStepIds = input.includeStepIds,
            includeSolutionIds = input.includeSolutionIds,
            excludedSnippetMarkerIds = input.excludedSnippetMarkerIds,
            excludedAttachmentMarkerIds = input.excludedAttachmentMarkerIds,
            options = input.options,
        )

        val slug = generateUniqueSlug()
        val publishment = CasePublishment.create(
            slug = slug,
            ownerUserId = requesterUserId,
            originalCaseId = input.caseId,
            title = input.title?.takeIf { it.isNotBlank() } ?: full.title,
            summary = input.summary,
            visibility = input.visibility,
            contentSnapshot = snapshot,
            options = input.options,
        )
        val saved = repository.save(publishment)

        // 멱등 기록 저장 — 동시 중복 요청이면 여기서 IdempotencyConflictException (tx 롤백).
        idempotencyRecord.save(idempotencyKey, requesterUserId, requestHash, saved.slug)

        // 잔디용 activity event — outbox INSERT (도메인 트랜잭션과 atomic).
        activityEventPublisher.publish(
            ActivityEventPublisherPort.ActivityEvent(
                userId = requesterUserId,
                type = ActivityEventPublisherPort.Type.CASE_PUBLISHED,
                occurredAt = Instant.now(),
                idempotencyKey = "publish:${saved.slug}",
                meta = mapOf("originalCaseId" to input.caseId, "visibility" to input.visibility.name),
            )
        )

        return Result(publishment = saved, replayed = false)
    }

    private fun generateUniqueSlug(): String {
        // 충돌 매우 드물지만 안전망 — 3 회 재시도.
        repeat(3) {
            val s = SlugGenerator.generate(slugProperties.length)
            if (!repository.existsBySlug(s)) return s
        }
        // fallback — 더 길게
        return SlugGenerator.generate(slugProperties.length + 4)
    }
}

/**
 * 발행 전 미리보기 — 저장 없이 스냅샷만 만들어 transient `CasePublishment` 로 반환.
 * 컨트롤러가 이를 `HtmlRenderer` 로 렌더해 "발행하면 이렇게 보인다" 를 그대로 노출한다.
 *
 *  - `CreatePublishmentUseCase` 와 완전히 동일한 경로(core full-data → ContentSnapshotBuilder)를
 *    쓰므로 선별/마스킹/옵션이 실제 발행 결과와 1:1 (WYSIWYG).
 *  - core-api full-data 호출 시 owner 아니면 core-api 가 403 → 그대로 전파.
 *  - DB write 없음. slug 는 placeholder(`preview`), version/viewCount 은 create 기본값.
 */
@Service
class PreviewPublishmentUseCase(
    private val coreCaseData: CoreCaseDataPort,
) {
    fun invoke(requesterUserId: Long, input: PublishInput): CasePublishment {
        val full = coreCaseData.fetchFullData(input.caseId)
            ?: throw CoreCaseNotFoundException("case not found: ${input.caseId}")
        val snapshot = ContentSnapshotBuilder.build(
            full = full,
            includeStepIds = input.includeStepIds,
            includeSolutionIds = input.includeSolutionIds,
            excludedSnippetMarkerIds = input.excludedSnippetMarkerIds,
            excludedAttachmentMarkerIds = input.excludedAttachmentMarkerIds,
            options = input.options,
        )
        return CasePublishment.create(
            slug = PREVIEW_SLUG,
            ownerUserId = requesterUserId,
            originalCaseId = input.caseId,
            title = input.title?.takeIf { it.isNotBlank() } ?: full.title,
            summary = input.summary,
            visibility = input.visibility,
            contentSnapshot = snapshot,
            options = input.options,
        )
    }

    companion object {
        /** transient 미리보기용 placeholder slug — 저장 안 되므로 링크로 쓰이지 않음. */
        const val PREVIEW_SLUG = "preview"
    }
}

@Service
class GetPublishmentBySlugUseCase(
    private val repository: CasePublishmentRepositoryPort,
) {
    /**
     * 발행물 조회 — **순수 읽기(부작용 없음)**. 조회수 집계는 분리(`RecordPublishmentViewUseCase`).
     * (기존엔 여기서 incrementViewCount 를 호출 → PDF/JSON/소유자 관리 조회까지 카운트되는 over-count +
     *  읽기 경로마다 쓰기 트랜잭션(write-on-read) 문제. view 는 공개 페이지에서만 명시 집계.)
     */
    @Transactional(readOnly = true)
    fun invoke(slug: String, viewerUserId: Long?): CasePublishment {
        val p = repository.findBySlug(slug)
            ?: throw PublishmentNotFoundException("publishment not found: $slug")
        // 내려진(UNPUBLISHED) 발행물은 공개 라우트에서 410 — 단 소유자는 미리보기 허용.
        if (p.status == PublishmentStatus.UNPUBLISHED && p.ownerUserId != viewerUserId) {
            throw PublishmentGoneException("이 발행물은 작성자가 비공개(내리기) 처리했습니다")
        }
        // PUBLIC/UNLISTED 모두 링크로 접근 가능(PRIVATE 제거). visibility 는 discovery 노출만 좌우.
        return p
    }
}

/**
 * 케이스의 canonical 발행물 단건 조회 (요청자 소유 한정) — 케이스 상세의 "발행하기 / 발행됨(링크)" 판별용.
 * 케이스당 1 canonical 정책이라 단건. 발행 안 됐거나 요청자 소유가 아니면 null(컨트롤러가 404) —
 * 타인 발행물 존재 여부가 새지 않도록 소유 불일치도 null 로 취급.
 */
@Service
class GetMyPublishmentByCaseUseCase(
    private val repository: CasePublishmentRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(caseId: Long, requesterUserId: Long): CasePublishment? =
        repository.findByOriginalCaseId(caseId)?.takeIf { it.ownerUserId == requesterUserId }
}

/**
 * 조회수 집계 — **공개 페이지(`GET /p/{slug}`)에서만** 호출. 쿠키 dedup(방문자당 24h 1회)은 컨트롤러가 게이팅.
 * PDF 다운로드·JSON 단건 조회·소유자 관리 조회는 view 로 치지 않는다.
 */
@Service
class RecordPublishmentViewUseCase(
    private val repository: CasePublishmentRepositoryPort,
) {
    @Transactional
    fun invoke(slug: String) {
        repository.incrementViewCount(slug)
    }
}

/**
 * 다운로드 집계 — export(PDF/MD) 요청마다 +1. view 와 **분리 지표**.
 * 다운로드는 의도적 행위라 요청당 카운트(쿠키 dedup 안 함). 소유자 제외/봇 필터는 MVP2.
 */
@Service
class RecordPublishmentDownloadUseCase(
    private val repository: CasePublishmentRepositoryPort,
) {
    @Transactional
    fun invoke(slug: String) {
        repository.incrementDownloadCount(slug)
    }
}

/**
 * 콘텐츠 재발행(B축) — 원본 case 재조회 → 스냅샷 재생성 → 내용 바뀌면 version++.
 * `POST /by-slug/{slug}/republish`. 표현/메타만 바꾸려면 경량 PATCH(UpdatePublishmentMetadataUseCase) 사용.
 */
@Service
class RepublishPublishmentUseCase(
    private val coreCaseData: CoreCaseDataPort,
    private val repository: CasePublishmentRepositoryPort,
) {
    // noRollbackFor: SOURCE_DELETED 마킹(아래 catch)을 살리기 위해 이 예외는 롤백하지 않고 커밋.
    //   전제 — 이 예외를 던지는 지점(sourceState 단락 / catch)의 pending write 는 "의도한 markSourceDeleted" 뿐이어야 함.
    //   (fetchFullData 앞이나 catch 안에 다른 write 를 추가하면 함께 커밋되니 주의.)
    @Transactional(noRollbackFor = [PublishmentSourceDeletedException::class])
    fun invoke(slug: String, requesterUserId: Long, input: PublishInput): CasePublishment {
        val existing = repository.findBySlug(slug)
            ?: throw PublishmentNotFoundException("publishment not found: $slug")
        if (existing.ownerUserId != requesterUserId) {
            throw PublishmentForbiddenException("not the owner")
        }
        // 원본이 이미 삭제로 판정된 발행물은 재발행 불가 (core 호출 없이 단락).
        if (existing.sourceState == SourceState.SOURCE_DELETED) {
            throw PublishmentSourceDeletedException("원본 케이스가 삭제되어 재발행할 수 없습니다")
        }
        val full = try {
            coreCaseData.fetchFullData(existing.originalCaseId)
        } catch (e: CoreCaseNotFoundException) {
            // republish 시점에 원본이 삭제됨을 발견 → 발행물에 SOURCE_DELETED 표시 후 409.
            // (noRollbackFor 덕에 이 UPDATE 는 예외와 함께 커밋되어 persist 됨 — 다음 재발행은 위 단락으로 409.)
            existing.markSourceDeleted()
            repository.markSourceDeleted(existing.id!!)
            throw PublishmentSourceDeletedException("원본 케이스가 삭제되어 재발행할 수 없습니다")
        } ?: throw CoreCaseNotFoundException("original case not found: ${existing.originalCaseId}")
        val snapshot = ContentSnapshotBuilder.build(
            full, input.includeStepIds, input.includeSolutionIds,
            input.excludedSnippetMarkerIds, input.excludedAttachmentMarkerIds,
            input.options,
        )
        existing.republish(
            newTitle = input.title?.takeIf { it.isNotBlank() } ?: existing.title,
            newSummary = input.summary,
            newVisibility = input.visibility,
            newSnapshot = snapshot,
            newOptions = input.options,
        )
        return repository.update(existing)
    }
}

/**
 * 경량 메타 편집(A축) — `PATCH /by-slug/{slug}`. 스냅샷/원본 조회 없이 title/summary/visibility/options 만 교체.
 * version 유지 · 원본 삭제(SOURCE_DELETED)여도 허용. partial: 제공된 필드만 반영(summary 는 provided-null=미변경, "" 로 비움).
 */
@Service
class UpdatePublishmentMetadataUseCase(
    private val repository: CasePublishmentRepositoryPort,
) {
    data class Input(
        val title: String?,
        val summary: String?,
        val visibility: Visibility?,
        val options: PublishOptions?,
    )

    @Transactional
    fun invoke(slug: String, requesterUserId: Long, input: Input): CasePublishment {
        val p = repository.findBySlug(slug)
            ?: throw PublishmentNotFoundException("publishment not found: $slug")
        if (p.ownerUserId != requesterUserId) {
            throw PublishmentForbiddenException("not the owner")
        }
        p.editMetadata(
            newTitle = input.title?.takeIf { it.isNotBlank() } ?: p.title,
            newSummary = input.summary ?: p.summary,
            newVisibility = input.visibility ?: p.visibility,
            newOptions = input.options ?: p.options,
        )
        return repository.update(p)
    }
}

/** 내리기(soft) — `POST /by-slug/{slug}/unpublish`. status=UNPUBLISHED. slug·스냅샷·counters 보존. */
@Service
class UnpublishPublishmentUseCase(
    private val repository: CasePublishmentRepositoryPort,
) {
    @Transactional
    fun invoke(slug: String, requesterUserId: Long): CasePublishment {
        val p = repository.findBySlug(slug)
            ?: throw PublishmentNotFoundException("publishment not found: $slug")
        if (p.ownerUserId != requesterUserId) {
            throw PublishmentForbiddenException("not the owner")
        }
        p.unpublish()
        return repository.update(p)
    }
}

/** 다시 공개 — `POST /by-slug/{slug}/publish`. UNPUBLISHED → LIVE 복구. */
@Service
class RestorePublishmentUseCase(
    private val repository: CasePublishmentRepositoryPort,
) {
    @Transactional
    fun invoke(slug: String, requesterUserId: Long): CasePublishment {
        val p = repository.findBySlug(slug)
            ?: throw PublishmentNotFoundException("publishment not found: $slug")
        if (p.ownerUserId != requesterUserId) {
            throw PublishmentForbiddenException("not the owner")
        }
        p.restore()
        return repository.update(p)
    }
}

@Service
class DeletePublishmentUseCase(
    private val repository: CasePublishmentRepositoryPort,
    private val idempotencyRecord: IdempotencyRecordPort,
) {
    @Transactional
    fun invoke(slug: String, requesterUserId: Long) {
        val p = repository.findBySlug(slug)
            ?: throw PublishmentNotFoundException("publishment not found: $slug")
        if (p.ownerUserId != requesterUserId) {
            throw PublishmentForbiddenException("not the owner")
        }
        repository.deleteById(p.id!!)
        // hard delete 시 연결된 멱등 기록도 같은 tx 에서 정리 — case_publishment 로의 FK 가 없어
        // 남으면 고아(정리 잡은 48h). slug 재사용/키 재사용 시 stale replay 방지.
        idempotencyRecord.deleteBySlug(slug)
    }
}

/**
 * 공개 discovery — visibility=PUBLIC 발행물만. 검색엔진 sitemap / 향후 공개 목록 표면의 소스.
 * **별도 검색 인덱스를 두지 않고 live 쿼리**로 노출 → visibility 를 UNLISTED 로 바꾸면 다음 조회부터
 * 자동 제외(동기화 코드 불필요). 인증 불필요(공개).
 */
@Service
class ListPublicPublishmentsUseCase(
    private val repository: CasePublishmentRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(limit: Int = 500): List<CasePublishment> =
        repository.listPublicDiscoverable().take(limit)
}

@Service
class ListMyPublishmentsUseCase(
    private val repository: CasePublishmentRepositoryPort,
    private val originalStatusReader: OriginalCaseStatusReaderPort,
) {
    /** 발행물 + 원본 lazy 판정 결과(stale). sourceState 는 publishment 에 들어있음. */
    data class Item(
        val publishment: CasePublishment,
        /** 원본이 발행 이후 수정됨(재발행 권장). null = 판정 불가(core 조회 실패/원본 삭제). */
        val sourceStale: Boolean?,
    )

    data class Input(
        val requesterUserId: Long,
        val q: String?,
        val visibility: Visibility?,
        val sort: MyPublishmentSort,
        val cursor: String?,
        val limit: Int,
    )

    data class Result(
        val items: List<Item>,
        val nextCursor: String?,
        val hasNext: Boolean,
        /** 첫 페이지(cursor 없음)에서만 계산. 이후 페이지는 null. */
        val totalCount: Long?,
    )

    /**
     * 소유자의 발행물 목록 — keyset 페이징 + 검색/필터/정렬 + **원본 lazy 판정**:
     *  - `pageByOwner` 로 현재 페이지(limit+1)만 조회 → hasNext 판정.
     *  - 현재 페이지 항목의 원본만 `POST /internal/error-cases/status` 배치 조회:
     *    - 응답에 없는 원본 = 삭제 → 발행물 SOURCE_DELETED persist(발행물은 유지)
     *    - `updatedAt > publishment.updatedAt` = STALE → 재발행 권장
     *    - core 조회 실패(null) 시 판정 skip(기존 상태 그대로, 오판 방지)
     *  - `totalCount` 는 첫 페이지에서만 계산.
     */
    @Transactional
    fun invoke(input: Input): Result {
        val pageSize = input.limit.coerceIn(1, MAX_LIMIT)
        val decoded = input.cursor?.let { PublishmentCursor.decode(it) }

        val query = MyPublishmentQuery(
            ownerUserId = input.requesterUserId,
            q = input.q,
            visibility = input.visibility,
            sort = input.sort,
            cursorValue = decoded?.value,
            cursorId = decoded?.id,
            limit = pageSize + 1,   // +1 로 hasNext 판정
        )
        val fetched = repository.pageByOwner(query)
        val hasNext = fetched.size > pageSize
        val page = fetched.take(pageSize)

        val items = enrichWithSourceState(page)

        val nextCursor = if (hasNext && page.isNotEmpty()) {
            val last = page.last()
            PublishmentCursor.encode(sortValueOf(last, input.sort), last.id!!)
        } else {
            null
        }
        val totalCount = if (input.cursor == null) repository.countByOwner(query) else null

        return Result(items = items, nextCursor = nextCursor, hasNext = hasNext, totalCount = totalCount)
    }

    /** 현재 페이지 항목에 대해서만 원본 lazy 판정(stale/deleted). */
    private fun enrichWithSourceState(page: List<CasePublishment>): List<Item> {
        if (page.isEmpty()) return emptyList()
        val caseIds = page.map { it.originalCaseId }.toSet()
        val statuses = originalStatusReader.fetchStatuses(caseIds)
            ?: return page.map { Item(it, sourceStale = null) }  // core 조회 실패 → 판정 skip

        return page.map { p ->
            val st = statuses[p.originalCaseId]
            if (st == null) {
                if (p.sourceState != SourceState.SOURCE_DELETED) {
                    repository.markSourceDeleted(p.id!!)
                    p.markSourceDeleted()
                }
                Item(p, sourceStale = null)
            } else {
                Item(p, sourceStale = st.updatedAt.isAfter(p.updatedAt))
            }
        }
    }

    private fun sortValueOf(p: CasePublishment, sort: MyPublishmentSort): String = when (sort) {
        MyPublishmentSort.PUBLISHED -> p.publishedAt.toString()
        MyPublishmentSort.VIEWS -> p.viewCount.toString()
    }

    companion object {
        private const val MAX_LIMIT = 100
    }
}

/**
 * 내 발행물 툴바 요약 — visibility별 카운트 + 총 조회수 합계.
 * 검색/필터와 무관하게 소유자 전체 발행물 기준 (툴바는 항상 라이브러리 전체를 요약).
 */
@Service
class GetMyPublishmentSummaryUseCase(
    private val repository: CasePublishmentRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(ownerUserId: Long): OwnerPublishmentSummary = repository.summaryOf(ownerUserId)
}

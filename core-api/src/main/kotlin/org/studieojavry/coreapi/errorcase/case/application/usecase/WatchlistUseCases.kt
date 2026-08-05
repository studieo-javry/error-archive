package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.CaseViewRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.CaseWatchlistRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.shared.config.DashboardCacheInvalidator
import java.time.LocalDateTime
import java.util.Base64

/**
 * Watchlist 추가 — 사용자가 *case 활동을 지켜보겠다* 는 명시적 follow.
 *
 * 권한: case 읽기 가능 (PUBLIC 또는 워크스페이스 멤버 또는 owner). PRIVATE 비-owner 는 403.
 * 멱등: 이미 있으면 200 + 기존 row 반환.
 */
@Service
class AddCaseToWatchlistUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val watchlistRepository: CaseWatchlistRepositoryPort,
    private val access: ErrorCaseAccess,
    private val cacheInvalidator: DashboardCacheInvalidator,
) {
    @Transactional
    fun invoke(errorCaseId: Long, userId: Long) {
        val errorCase = errorCaseRepository.findById(errorCaseId)
            ?: throw ErrorCaseNotFoundException(errorCaseId)
        access.requireRead(errorCase, userId)
        watchlistRepository.add(errorCaseId, userId)
        runCatching { cacheInvalidator.evictWatchlistFeed(userId) }
    }
}

@Service
class RemoveCaseFromWatchlistUseCase(
    private val watchlistRepository: CaseWatchlistRepositoryPort,
    private val cacheInvalidator: DashboardCacheInvalidator,
) {
    /** 권한 검증 X — 자기 자신의 watchlist 만 제거. 본인 row 만 삭제 query 라 cross-user 영향 없음. */
    @Transactional
    fun invoke(errorCaseId: Long, userId: Long) {
        watchlistRepository.remove(errorCaseId, userId)
        runCatching { cacheInvalidator.evictWatchlistFeed(userId) }
    }
}

/**
 * Library > Watchlist 페이지 · **관리용** 목록.
 *
 * 홈 위젯 (`GetMyWatchlistFeedUseCase`) 과 다른 관점:
 *  - 홈 위젯: 활동 감지 · 상위 20 · latest activity actor 등
 *  - 이 UseCase: 컬렉션 관리 · 전체 목록 · sort/search/cursor · watchlist-level metadata (addedAt)
 *
 * 각 item 필드:
 *  - `summary` (ErrorCaseSummary — visibility/title/tags/... 등 case-level 정보)
 *  - `addedAt` (case_watchlist.created_at — 담은 시각)
 *  - `lastActivityAt` (case.updatedAt / comment / step / solution 최대치)
 *  - `unreadCount` (내 case_view.lastViewedAt 이후 남 활동 수)
 *
 * **정렬** (`sort`):
 *  - `LAST_ACTIVITY` (default): (unread > 0 desc, lastActivityAt desc)
 *  - `ADDED`: addedAt desc
 *  - `TITLE`: title asc (case-insensitive)
 *
 * **검색** (`search`): title 또는 tag 부분 매치 (case-insensitive).
 *
 * **Cursor**: offset 기반 (base64 encoded "offset:N"). 사용자당 watchlist 규모가 bounded (수십~수백)
 * 라 전체 fetch 후 in-memory sort/filter/slice. DB level cursor 는 scale 확장 시 후속.
 */
@Service
class GetMyWatchlistUseCase(
    private val watchlistRepository: CaseWatchlistRepositoryPort,
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
    private val caseViewRepository: CaseViewRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(input: Input): Result {
        val limit = input.limit.coerceIn(1, MAX_LIMIT)
        val offset = decodeCursor(input.cursor)

        // 1. Fetch all entries (bounded to MAX_FETCH — 사용자당 watchlist 상한 500)
        val entries = watchlistRepository.findEntriesByUserId(input.userId, MAX_FETCH)
        if (entries.isEmpty()) return emptyResult()

        // 2. Fetch case summaries batch
        val allCaseIds = entries.map { it.caseId }.toSet()
        val summaries: Map<Long, ErrorCaseSummary> = errorCaseRepository.findSummariesByIds(allCaseIds)
            .associateBy { it.id }

        // 3. Search filter (title / tags, case-insensitive)
        val searched = if (input.search.isNullOrBlank()) entries
        else {
            val q = input.search.trim().lowercase()
            entries.filter { entry ->
                val s = summaries[entry.caseId] ?: return@filter false
                s.title.lowercase().contains(q) || s.tags.any { it.lowercase().contains(q) }
            }
        }
        if (searched.isEmpty()) return emptyResult()

        // 4. Activity + unread batch — 정렬 및 표시용
        val filteredCaseIds = searched.map { it.caseId }
        val lastViewedByCase = caseViewRepository.findByUserAndCaseIds(input.userId, filteredCaseIds)
        val activities = commentRepository.findActivitiesByCaseIdsSince(filteredCaseIds, EPOCH) +
            stepRepository.findActivitiesByCaseIdsSince(filteredCaseIds, EPOCH) +
            solutionRepository.findActivitiesByCaseIdsSince(filteredCaseIds, EPOCH)

        val lastActivityByCase: Map<Long, LocalDateTime> = activities
            .groupBy { it.errorCaseId }
            .mapValues { (_, rows) -> rows.maxOf { it.createdAt } }

        val unreadByCase: Map<Long, Long> = activities
            .filter { it.authorUserId != input.userId }
            .filter {
                val lv = lastViewedByCase[it.errorCaseId]
                lv == null || it.createdAt.isAfter(lv)
            }
            .groupingBy { it.errorCaseId }
            .eachCount()
            .mapValues { it.value.toLong() }

        // 5. Build items — lastActivityAt 는 case.updatedAt / activity / resolvedAt 중 max
        val items = searched.mapNotNull { entry ->
            val summary = summaries[entry.caseId] ?: return@mapNotNull null
            val lastAct = listOfNotNull(
                summary.updatedAt,
                lastActivityByCase[entry.caseId],
                summary.resolvedAt,
            ).maxOrNull()
            Item(
                summary = summary,
                addedAt = entry.addedAt,
                lastActivityAt = lastAct,
                unreadCount = unreadByCase[entry.caseId] ?: 0L,
            )
        }

        // 6. Filter — unread(unreadCount>0). statusCounts 는 status 필터 *제외* 상태에서 집계
        //    (칩 카운트가 "이 status 클릭 시 몇 건" 을 예측하도록).
        val afterUnread = if (input.unreadOnly) items.filter { it.unreadCount > 0L } else items
        val statusCounts = ALL_STATUSES.associateWith { st ->
            afterUnread.count { it.summary.status == st }.toLong()
        }
        val filtered = if (input.status.isNullOrEmpty()) afterUnread
        else afterUnread.filter { it.summary.status in input.status }

        // 7. Sort
        val sorted = when (input.sort) {
            Sort.ADDED -> filtered.sortedByDescending { it.addedAt }
            Sort.TITLE -> filtered.sortedBy { it.summary.title.lowercase() }
            Sort.LAST_ACTIVITY -> filtered.sortedWith(
                compareByDescending<Item> { it.unreadCount > 0L }
                    .thenByDescending { it.lastActivityAt ?: EPOCH }
            )
        }

        // 8. Slice by offset cursor
        val page = sorted.drop(offset).take(limit)
        val nextOffset = offset + page.size
        val hasNext = nextOffset < sorted.size
        val nextCursor = if (hasNext) encodeCursor(nextOffset) else null

        return Result(
            items = page,
            nextCursor = nextCursor,
            hasNext = hasNext,
            totalCount = sorted.size.toLong(),
            statusCounts = statusCounts,
        )
    }

    private fun emptyResult() = Result(
        items = emptyList(),
        nextCursor = null,
        hasNext = false,
        totalCount = 0L,
        statusCounts = ALL_STATUSES.associateWith { 0L },
    )

    private fun decodeCursor(cursor: String?): Int {
        if (cursor.isNullOrBlank()) return 0
        return runCatching {
            val decoded = Base64.getUrlDecoder().decode(cursor).decodeToString()
            decoded.removePrefix("offset:").toInt().coerceAtLeast(0)
        }.getOrDefault(0)
    }

    private fun encodeCursor(offset: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("offset:$offset".toByteArray())

    enum class Sort {
        /** 활동 최근순 · (unread>0 desc, lastActivityAt desc). Default. */
        LAST_ACTIVITY,
        /** 담은 시각 최근순 (case_watchlist.created_at desc). */
        ADDED,
        /** case.title asc (case-insensitive). */
        TITLE,
        ;
        companion object {
            fun fromCode(code: String?): Sort = when (code?.lowercase()) {
                "added" -> ADDED
                "title" -> TITLE
                else -> LAST_ACTIVITY // default 포함 unknown
            }
        }
    }

    data class Input(
        val userId: Long,
        val sort: Sort = Sort.LAST_ACTIVITY,
        val search: String? = null,
        /** status 필터(다중값, OR). null/empty = 전체. */
        val status: Set<ErrorCaseStatus>? = null,
        /** true = 안 읽은 업데이트 있는 것만(unreadCount>0). */
        val unreadOnly: Boolean = false,
        val cursor: String? = null,
        val limit: Int = DEFAULT_LIMIT,
    )

    data class Item(
        val summary: ErrorCaseSummary,
        val addedAt: LocalDateTime,
        val lastActivityAt: LocalDateTime?,
        val unreadCount: Long,
    )

    data class Result(
        val items: List<Item>,
        val nextCursor: String?,
        val hasNext: Boolean,
        /** 필터 적용 후 전체 건수(현재 페이지 아님) — "N개 중 X개" 표시용. */
        val totalCount: Long,
        /** status별 건수(status 필터 제외, search·unread 반영) — 필터 칩 카운트용. 전 status 포함(0 포함). */
        val statusCounts: Map<ErrorCaseStatus, Long>,
    )

    companion object {
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 100
        /** 사용자당 watchlist 규모 상한 — 이 이상은 잘라냄 (in-memory sort 안전선). */
        const val MAX_FETCH = 500
        private val EPOCH: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
        /** statusCounts 는 항상 전 status 를 0 포함으로 반환(FE 칩이 고정 렌더되도록). */
        private val ALL_STATUSES: List<ErrorCaseStatus> = ErrorCaseStatus.entries
    }
}

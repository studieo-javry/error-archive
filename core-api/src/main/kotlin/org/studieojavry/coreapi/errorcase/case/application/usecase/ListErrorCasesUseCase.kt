package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.case.application.port.SortBy
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import java.time.LocalDateTime
import java.util.Base64

/**
 * 에러케이스 목록 조회 — cursor(keyset) 기반 무한 스크롤.
 *
 * 스코프/권한:
 *  - workspaceId 지정: 그 워크스페이스 멤버(READ+)면 **워크스페이스 전체 케이스**, 비멤버 거부.
 *  - workspaceId 미지정: 요청자 **본인 소유 케이스**(개인 + 소유한 워크스페이스 케이스).
 *
 * 정렬 createdAt DESC, id DESC. hasNext 판정을 위해 size+1 을 조회한다.
 */
@Service
class ListErrorCasesUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort
) {
    @Transactional(readOnly = true)
    fun invoke(input: Input): Result {
        val ownerFilter: Long? = if (input.workspaceId != null) {
            if (workspaceQuery.getViewerRole(input.requesterUserId, input.workspaceId)?.canRead() != true) {
                throw ErrorCaseAccessDeniedException("not a member of workspace ${input.workspaceId}")
            }
            null // 워크스페이스 전체
        } else {
            input.requesterUserId // 내 케이스
        }

        val size = input.size.coerceIn(1, MAX_SIZE)
        val cursor = decodeCursor(input.cursor)

        val rows = errorCaseRepository.search(
            ErrorCaseSearchCriteria(
                workspaceId = input.workspaceId,
                ownerUserId = ownerFilter,
                status = input.status,
                fingerprint = input.fingerprint,
                visibility = input.visibility,
                cursorCreatedAt = cursor?.first,
                cursorId = cursor?.second,
                limit = size + 1,
                q = input.q,
                sortBy = input.sortBy,
            )
        )

        val hasNext = rows.size > size
        val items = if (hasNext) rows.take(size) else rows
        val nextCursor = items.lastOrNull()
            ?.takeIf { hasNext }
            ?.let { last ->
                val sortAt = when (input.sortBy) {
                    SortBy.CREATED -> last.createdAt
                    SortBy.UPDATED -> last.updatedAt
                }
                encodeCursor(sortAt, last.id)
            }

        return Result(items = items, nextCursor = nextCursor, hasNext = hasNext)
    }

    private fun encodeCursor(sortAt: LocalDateTime, id: Long): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$sortAt|$id".toByteArray(Charsets.UTF_8))

    /** 잘못된 커서는 무시(null) → 처음부터. */
    private fun decodeCursor(cursor: String?): Pair<LocalDateTime, Long>? {
        if (cursor.isNullOrBlank()) return null
        return runCatching {
            val raw = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
            val sep = raw.lastIndexOf('|')
            val createdAt = LocalDateTime.parse(raw.substring(0, sep))
            val id = raw.substring(sep + 1).toLong()
            createdAt to id
        }.getOrNull()
    }

    data class Input(
        val requesterUserId: Long,
        val workspaceId: Long?,
        val status: ErrorCaseStatus?,
        val fingerprint: String?,
        /** visibility 필터(PUBLIC/WORKSPACE/PRIVATE). null 이면 미적용. */
        val visibility: Visibility? = null,
        val cursor: String?,
        val size: Int,
        /** 검색어 — title 또는 tag 부분 매치 (case-insensitive). */
        val q: String? = null,
        /** 정렬 · CREATED (default) / UPDATED. */
        val sortBy: SortBy = SortBy.CREATED,
    )

    data class Result(
        val items: List<ErrorCaseSummary>,
        val nextCursor: String?,
        val hasNext: Boolean
    )

    companion object {
        private const val MAX_SIZE = 100
    }
}

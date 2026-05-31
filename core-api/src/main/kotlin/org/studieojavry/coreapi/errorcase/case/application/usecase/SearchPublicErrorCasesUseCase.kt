package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import java.time.LocalDateTime
import java.util.Base64

/**
 * 공개(PUBLIC) 에러 케이스 검색 — 인증된 사용자라면 누구나.
 *
 * `ListErrorCasesUseCase` 와 분리한 이유:
 *  - 의도 + 권한 모델이 다름 (본인/워크스페이스 vs 전체 공개)
 *  - 향후 본 endpoint 에 *공개 한정 기능*(인기/추천/정렬 옵션)이 붙기 좋게
 *
 * 정렬: `createdAt DESC, id DESC`. cursor 인코딩은 ListUseCase 와 호환.
 */
@Service
class SearchPublicErrorCasesUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(input: Input): Result {
        val size = input.size.coerceIn(1, MAX_SIZE)
        val cursor = decodeCursor(input.cursor)

        val rows = errorCaseRepository.search(
            ErrorCaseSearchCriteria(
                workspaceId = null,
                ownerUserId = null,
                status = input.status,
                severityCode = input.severity,
                fingerprint = input.fingerprint,
                visibility = Visibility.PUBLIC,
                cursorCreatedAt = cursor?.first,
                cursorId = cursor?.second,
                limit = size + 1,
            )
        )

        val hasNext = rows.size > size
        val items = if (hasNext) rows.take(size) else rows
        val nextCursor = items.lastOrNull()
            ?.takeIf { hasNext }
            ?.let { encodeCursor(it.createdAt, it.id) }

        return Result(items = items, nextCursor = nextCursor, hasNext = hasNext)
    }

    private fun encodeCursor(createdAt: LocalDateTime, id: Long): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$createdAt|$id".toByteArray(Charsets.UTF_8))

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
        val status: ErrorCaseStatus?,
        val severity: Int?,
        val fingerprint: String?,
        val cursor: String?,
        val size: Int,
    )

    data class Result(
        val items: List<ErrorCaseSummary>,
        val nextCursor: String?,
        val hasNext: Boolean,
    )

    companion object {
        private const val MAX_SIZE = 100
    }
}

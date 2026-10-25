package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSummary
import org.studieojavry.coreapi.errorcase.case.application.port.SimilarCaseSearchPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort

/**
 * 유사 에러 추천 — 에러 케이스 제목을 입력하는 동안 비슷한 기존 사례를 실시간으로 찾는다.
 *
 * 목적: 중복 생성 방지 + 기존 해결책 재사용.
 * 스코프/권한(목록 조회와 동일):
 *  - `workspaceId` 지정: 그 워크스페이스 멤버(READ+)면 워크스페이스 전체, 비멤버 거부(403).
 *  - `workspaceId` 미지정: 요청자 본인 소유 케이스.
 * → 남의 PRIVATE 제목이 새지 않도록 반드시 이 게이트를 통과한 뒤 검색한다.
 */
@Service
class FindSimilarCasesUseCase(
    private val similarSearch: SimilarCaseSearchPort,
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort,
) {
    data class View(
        val summary: ErrorCaseSummary,
        /** 0~1. 소수 셋째 자리 반올림. */
        val score: Double,
        /** 하이라이트용 — 입력 토큰 중 제목/태그에 매칭된 것. */
        val matchedTerms: List<String>,
    )

    @Transactional(readOnly = true)
    fun invoke(q: String, requesterUserId: Long, workspaceId: Long?, limit: Int): List<View> {
        val query = q.trim()
        if (query.length < MIN_QUERY_LEN) return emptyList()

        val ownerFilter: Long? = if (workspaceId != null) {
            if (workspaceQuery.getViewerRole(requesterUserId, workspaceId)?.canRead() != true) {
                throw ErrorCaseAccessDeniedException("not a member of workspace $workspaceId")
            }
            null // 워크스페이스 전체
        } else {
            requesterUserId // 내 케이스
        }

        val n = limit.coerceIn(1, MAX_LIMIT)
        val hits = similarSearch.search(query, workspaceId, ownerFilter, MIN_SCORE, n)
        if (hits.isEmpty()) return emptyList()

        // id → summary (tags/상태/본문 포함). 삭제됐거나 조회 불가한 id 는 제외. 히트 순서(점수 내림차순) 유지.
        val byId = errorCaseRepository.findSummariesByIds(hits.map { it.caseId }).associateBy { it.id }
        val qTokens = tokenize(query)
        return hits.mapNotNull { hit ->
            byId[hit.caseId]?.let { s ->
                View(summary = s, score = round3(hit.score), matchedTerms = matchedTerms(qTokens, s))
            }
        }
    }

    private fun matchedTerms(qTokens: List<String>, s: ErrorCaseSummary): List<String> {
        val hay = tokenize(s.title) + s.tags.map { it.lowercase() }
        return qTokens.filter { t -> hay.any { it == t || (t.length >= 3 && it.startsWith(t)) || (it.length >= 3 && t.startsWith(it)) } }
            .distinct()
    }

    private fun tokenize(s: String): List<String> =
        Regex("[a-z0-9]+|[가-힣]+").findAll(s.lowercase()).map { it.value }.filter { it.length >= 2 }.toList()

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    companion object {
        private const val MIN_QUERY_LEN = 2
        private const val MAX_LIMIT = 10
        /** 이 미만 유사도는 노이즈로 제외(표시 밴드는 FE 가 결정). */
        private const val MIN_SCORE = 0.2
    }
}

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
        /**
         * "중복 가능성 높음" 강한 신호. score ≥ [STRONG_DUP_SCORE] **그리고** 입력·후보 제목의
         * 예외 클래스(`*Exception`/`*Error`)가 하나라도 겹칠 때만 true. 표면 유사도만으로 겁주지
         * 않기 위한 보수적 판정(실측상 score 단독 0.70 임계는 오탐 24~31%). 소프트 추천은 그대로 노출.
         */
        val likelyDuplicate: Boolean,
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
        val qExceptions = DuplicateHint.exceptionClasses(query)
        return hits.mapNotNull { hit ->
            byId[hit.caseId]?.let { s ->
                val score = round3(hit.score)
                View(
                    summary = s,
                    score = score,
                    matchedTerms = matchedTerms(qTokens, s),
                    likelyDuplicate = DuplicateHint.isLikelyDuplicate(score, qExceptions, s.title),
                )
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
        /** 이 미만 유사도는 노이즈로 제외(소프트 추천 표시는 FE 가 score 로 결정). */
        private const val MIN_SCORE = 0.2
    }
}

/**
 * "중복 가능성 높음" 판정 규칙 — 유스케이스에서 분리해 단위 테스트 가능하게 둠.
 * pg_trgm 표면 유사도는 예외명 같은 공통 토큰에 과매칭되므로, 강한 중복 경고는
 * **점수 임계 + 예외 클래스 동일** 두 조건을 모두 만족할 때만 켠다.
 */
internal object DuplicateHint {
    /** score 이상 + 예외 클래스 공유 시 강한 중복으로 본다. 실측상 0.70 단독은 오탐 과다. */
    const val STRONG_DUP_SCORE = 0.85

    private val EXCEPTION_RE = Regex("[A-Za-z][A-Za-z0-9]*(?:exception|error)", RegexOption.IGNORE_CASE)

    /** 제목에서 `*Exception`/`*Error` 형태의 예외 클래스 토큰을 소문자로 추출. */
    fun exceptionClasses(text: String): Set<String> =
        EXCEPTION_RE.findAll(text).map { it.value.lowercase() }.toSet()

    /** 입력의 예외 클래스 집합이 비어있지 않고 후보 제목과 하나라도 겹치며 score 가 임계 이상이면 true. */
    fun isLikelyDuplicate(score: Double, queryExceptions: Set<String>, candidateTitle: String): Boolean {
        if (score < STRONG_DUP_SCORE || queryExceptions.isEmpty()) return false
        val candidate = exceptionClasses(candidateTitle)
        return queryExceptions.any { it in candidate }
    }
}

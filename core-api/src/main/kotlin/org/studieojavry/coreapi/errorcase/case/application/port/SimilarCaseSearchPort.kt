package org.studieojavry.coreapi.errorcase.case.application.port

/**
 * 제목 유사도 검색 포트 — "유사 에러 추천"(작성 중 실시간)의 교체 지점.
 *
 * 1단계 구현: Postgres `pg_trgm` word_similarity (표면/오타/부분일치).
 * 2단계(후속): 임베딩 벡터 유사도(pgvector) 로 어댑터만 교체 — UseCase/Controller/FE 는 그대로.
 *
 * 스코프는 호출자(UseCase)가 결정해 전달한다(가시성 게이트는 UseCase 책임):
 *  - `workspaceId != null`  → 그 워크스페이스 전체 케이스
 *  - `ownerUserId != null`  → 본인 소유 케이스
 * 둘 다 null 이면 전역(사용 안 함).
 */
interface SimilarCaseSearchPort {
    fun search(
        q: String,
        workspaceId: Long?,
        ownerUserId: Long?,
        minScore: Double,
        limit: Int,
    ): List<SimilarCaseHit>
}

/** 유사도 히트 — 케이스 id 와 0~1 점수(내림차순 정렬된 상태로 반환). */
data class SimilarCaseHit(
    val caseId: Long,
    val score: Double,
)

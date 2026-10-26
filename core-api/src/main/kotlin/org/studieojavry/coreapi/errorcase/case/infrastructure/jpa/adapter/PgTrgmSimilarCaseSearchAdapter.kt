package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.adapter

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.studieojavry.coreapi.errorcase.case.application.port.SimilarCaseHit
import org.studieojavry.coreapi.errorcase.case.application.port.SimilarCaseSearchPort

/**
 * [SimilarCaseSearchPort] 의 Postgres `pg_trgm` 구현.
 *
 * `word_similarity(q, title)` — 입력한 짧은 제목 q 가 기존 title 안의 어느 구절과 가장 닮았는지(0~1).
 * 짧은/부분 입력(타이핑 중)에 적합해 `similarity()` 보다 이 함수를 쓴다.
 * 스코프 파라미터가 null 이면 그 조건을 건너뛴다(NULL 비교는 bigint 캐스팅으로 파라미터 타입 명시).
 */
@Repository
class PgTrgmSimilarCaseSearchAdapter(
    @PersistenceContext private val em: EntityManager,
) : SimilarCaseSearchPort {

    override fun search(
        q: String,
        workspaceId: Long?,
        ownerUserId: Long?,
        minScore: Double,
        limit: Int,
    ): List<SimilarCaseHit> {
        val sql = """
            SELECT id, word_similarity(:q, title) AS score
            FROM error_case
            WHERE (CAST(:workspaceId AS bigint) IS NULL OR meta_workspace_id = CAST(:workspaceId AS bigint))
              AND (CAST(:ownerUserId AS bigint) IS NULL OR owner_user_id = CAST(:ownerUserId AS bigint))
              AND word_similarity(:q, title) >= :minScore
            ORDER BY score DESC, id DESC
            LIMIT :limit
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(sql)
            .setParameter("q", q)
            .setParameter("workspaceId", workspaceId)
            .setParameter("ownerUserId", ownerUserId)
            .setParameter("minScore", minScore)
            .setParameter("limit", limit)
            .resultList as List<Array<Any?>>

        return rows.map { r ->
            SimilarCaseHit(
                caseId = (r[0] as Number).toLong(),
                score = (r[1] as Number).toDouble(),
            )
        }
    }
}

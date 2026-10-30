package org.studieojavry.coreapi.errorcase.github.domain.model.vo

import java.time.Instant

/**
 * 커밋 하나의 CI 전체 결과. check_suite 는 CI 앱마다 따로 오지만, 사용자에게 의미 있는 단위는
 * "이 커밋의 CI 가 다 통과했나" 이므로 그 커밋의 check run 전부를 모아 한 줄로 요약한다.
 */
data class CiSummary(
    val sha: String,
    val conclusion: CiConclusion,
    val total: Int,
    val passed: Int,
    val failedNames: List<String>,
    val completedAt: Instant?,
) {
    enum class CiConclusion { SUCCESS, FAILURE, PENDING }

    /** check run 1건 (GitHub `status`/`conclusion` 원문 소문자). */
    data class Run(val name: String, val status: String, val conclusion: String?, val completedAt: Instant?)

    companion object {
        private const val MAX_FAILED_NAMES = 10
        private val PASSED = setOf("success", "neutral", "skipped")

        /** run 이 하나도 없으면 null (CI 를 안 쓰는 레포). 실패 1개라도 → FAILURE, 진행 중 있음 → PENDING. */
        fun of(sha: String, runs: List<Run>): CiSummary? {
            if (runs.isEmpty()) return null
            val completed = runs.filter { it.status == "completed" }
            val passed = completed.count { it.conclusion in PASSED }
            val failed = completed.filter { it.conclusion !in PASSED }.map { it.name }.distinct()
            val conclusion = when {
                failed.isNotEmpty() -> CiConclusion.FAILURE
                completed.size < runs.size -> CiConclusion.PENDING
                else -> CiConclusion.SUCCESS
            }
            return CiSummary(
                sha = sha,
                conclusion = conclusion,
                total = runs.size,
                passed = passed,
                failedNames = failed.take(MAX_FAILED_NAMES),
                completedAt = completed.mapNotNull { it.completedAt }.maxOrNull(),
            )
        }
    }
}

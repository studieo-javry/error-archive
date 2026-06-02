package org.studieojavry.coreapi.errorcase.solution.domain.model

import java.time.LocalDateTime

/**
 * 사용자가 step 들의 조합을 "최종 해결 방법" 으로 묶은 템플릿.
 * 한 케이스에 N 개 solution 가능(여러 해결 방법이 발견될 수 있음).
 * stepIds 의 순서는 유의미(사용자가 선택한 순서대로 보여줌).
 */
class Solution private constructor(
    val id: Long?,
    val errorCaseId: Long,
    val authorUserId: Long,
    var title: String,
    val stepIds: MutableList<Long>,
    val createdAt: LocalDateTime,
    var updatedAt: LocalDateTime
) {
    init {
        require(title.isNotBlank()) { "Solution title must not be blank" }
        require(title.length <= TITLE_MAX) { "Solution title must be $TITLE_MAX chars or less" }
        require(stepIds.isNotEmpty()) { "Solution must reference at least one step" }
        require(stepIds.distinct().size == stepIds.size) { "Solution stepIds must be unique" }
    }

    fun updateTitle(newTitle: String) {
        require(newTitle.isNotBlank()) { "Solution title must not be blank" }
        require(newTitle.length <= TITLE_MAX) { "Solution title must be $TITLE_MAX chars or less" }
        if (this.title == newTitle) return
        this.title = newTitle
        this.updatedAt = LocalDateTime.now()
    }

    companion object {
        const val TITLE_MAX = 200

        fun create(errorCaseId: Long, authorUserId: Long, title: String, stepIds: List<Long>): Solution {
            val now = LocalDateTime.now()
            return Solution(
                id = null,
                errorCaseId = errorCaseId,
                authorUserId = authorUserId,
                title = title,
                stepIds = stepIds.toMutableList(),
                createdAt = now,
                updatedAt = now
            )
        }

        fun rehydrate(
            id: Long,
            errorCaseId: Long,
            authorUserId: Long,
            title: String,
            stepIds: List<Long>,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime
        ): Solution = Solution(id, errorCaseId, authorUserId, title, stepIds.toMutableList(), createdAt, updatedAt)
    }
}
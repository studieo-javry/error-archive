package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import java.time.LocalDateTime

/**
 * 홈 대시보드의 "Recent My Activities" — 내가 최근 N일 안에 한 활동 timeline.
 *
 * 4 종류 활동 union:
 *  - CASE_CREATED — 내가 만든 케이스 (case.createdAt = case.updatedAt 인 것 + sinceInstant 이후)
 *  - CASE_RESOLVED — 내가 RESOLVED 로 전환한 케이스 (resolvedByUserId + resolvedAt 추적)
 *  - COMMENT_POSTED — 내가 작성한 댓글 (deleted 제외)
 *  - STEP_ADDED — 내가 추가한 step
 *  - SOLUTION_REGISTERED — 내가 등록한 solution
 *
 * 정렬: occurredAt DESC. limit: 30. 활동 발생 케이스의 title 은 일괄 fetch 로 보충.
 */
@Service
class GetMyRecentActivitiesUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val stepRepository: StepRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(input: Input): Result {
        val limit = input.limit.coerceIn(1, MAX_LIMIT)
        val sinceInstant = LocalDateTime.now().minusDays(input.windowDays.toLong())
        val perSourceLimit = limit + 5

        val activities = buildList {
            commentRepository.findRecentByAuthor(input.userId, sinceInstant, perSourceLimit).forEach {
                add(Item(type = ActivityType.COMMENT_POSTED, caseId = it.errorCaseId, occurredAt = it.createdAt))
            }
            stepRepository.findRecentByAuthor(input.userId, sinceInstant, perSourceLimit).forEach {
                add(Item(type = ActivityType.STEP_ADDED, caseId = it.errorCaseId, occurredAt = it.createdAt))
            }
            solutionRepository.findRecentByAuthor(input.userId, sinceInstant, perSourceLimit).forEach {
                add(Item(type = ActivityType.SOLUTION_REGISTERED, caseId = it.errorCaseId, occurredAt = it.createdAt))
            }
            // CASE_CREATED — 내 case 의 createdAt >= since
            errorCaseRepository.findRecentByOwner(input.userId, perSourceLimit)
                .filter { !it.createdAt.isBefore(sinceInstant) }
                .forEach {
                    add(Item(type = ActivityType.CASE_CREATED, caseId = it.id, occurredAt = it.createdAt))
                }
            // CASE_RESOLVED — 내가 RESOLVED 로 전환한 case (resolvedAt 시각)
            errorCaseRepository.findRecentlyResolvedByActor(input.userId, sinceInstant, perSourceLimit)
                .forEach {
                    val at = it.resolvedAt ?: return@forEach
                    add(Item(type = ActivityType.CASE_RESOLVED, caseId = it.id, occurredAt = at))
                }
        }
            .sortedByDescending { it.occurredAt }
            .take(limit)

        val caseIds = activities.map { it.caseId }.toSet()
        val titlesByCaseId = errorCaseRepository.findSummariesByIds(caseIds)
            .associateBy({ it.id }, { it.title })

        val hydrated = activities.map { it.copy(caseTitle = titlesByCaseId[it.caseId]) }

        // summary — *windowDays 전체* type 별 합계 (items 의 limit 무관). 5 count query.
        val summary = ActivitySummary(
            byType = mapOf(
                ActivityType.CASE_CREATED to errorCaseRepository.countCreatedByOwnerSince(input.userId, sinceInstant),
                ActivityType.CASE_RESOLVED to errorCaseRepository.countResolvedByActorSince(input.userId, sinceInstant),
                ActivityType.COMMENT_POSTED to commentRepository.countByAuthorSince(input.userId, sinceInstant),
                ActivityType.STEP_ADDED to stepRepository.countByAuthorSince(input.userId, sinceInstant),
                ActivityType.SOLUTION_REGISTERED to solutionRepository.countByAuthorSince(input.userId, sinceInstant),
            )
        )

        return Result(items = hydrated, windowDays = input.windowDays, summary = summary)
    }

    /** windowDays 전체의 type 별 합계 — pills 표시용. items 의 limit 와 무관. */
    data class ActivitySummary(val byType: Map<ActivityType, Long>)

    enum class ActivityType { CASE_CREATED, CASE_RESOLVED, COMMENT_POSTED, STEP_ADDED, SOLUTION_REGISTERED }

    data class Item(
        val type: ActivityType,
        val caseId: Long,
        val caseTitle: String? = null,
        val occurredAt: LocalDateTime,
    )

    data class Input(
        val userId: Long,
        val windowDays: Int = DEFAULT_WINDOW_DAYS,
        val limit: Int = DEFAULT_LIMIT,
    )

    data class Result(
        val items: List<Item>,
        val windowDays: Int,
        val summary: ActivitySummary,
    )

    companion object {
        private const val DEFAULT_WINDOW_DAYS = 7
        private const val DEFAULT_LIMIT = 30
        private const val MAX_LIMIT = 100
    }
}

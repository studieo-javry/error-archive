package org.studieojavry.coreapi.errorcase.sentry.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.sentry.application.port.SentryIssueCandidateRepositoryPort
import org.studieojavry.coreapi.errorcase.sentry.application.port.SentryProjectLinkPort
import org.studieojavry.coreapi.errorcase.sentry.config.ConditionalOnSentryEnabled
import org.studieojavry.coreapi.errorcase.sentry.domain.model.SentryIssueCandidate
import org.studieojavry.coreapi.errorcase.sentry.domain.model.vo.SentryIssueAction
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * 받은 편지함 한 건 처리 — Sentry 이슈를 워크스페이스의 Case 후보로 반영.
 *
 * - 신규(created) → 처음 보면 **PROPOSED 후보** 생성, 이미 있으면 발생 이력만 누적.
 * - 재발(unresolved=regression) → 연결된 Case 가 있으면 **REOPEN_PROPOSED**, 없으면 발생 이력.
 * - resource 가 issue 아님 / 이슈·프로젝트 식별 불가 / 미연결 프로젝트 → SKIPPED.
 */
@Component
@ConditionalOnSentryEnabled
class ProcessSentryWebhookEventUseCase(
    private val objectMapper: ObjectMapper,
    private val projectLinks: SentryProjectLinkPort,
    private val candidates: SentryIssueCandidateRepositoryPort,
) {
    private val log = KotlinLogging.logger {}

    enum class Result { DONE, SKIPPED }

    fun invoke(resource: String, action: String?, payload: String): Result {
        val root = objectMapper.readTree(payload)
        val issue = SentryIssueNormalizer.normalize(resource, root) ?: return Result.SKIPPED
        val link = projectLinks.findByProject(issue.projectSlug) ?: run {
            log.debug { "sentry issue from unmapped project '${issue.projectSlug}' — skipped" }
            return Result.SKIPPED
        }

        val now = Instant.now()
        val existing = candidates.find(issue.issueId)
        when (SentryIssueAction.from(action)) {
            SentryIssueAction.CREATED -> {
                if (existing == null) {
                    candidates.save(SentryIssueCandidate.propose(issue, link.workspaceId, now))
                } else {
                    existing.recordOccurrence(now)
                    candidates.save(existing)
                }
            }

            SentryIssueAction.REGRESSION -> {
                if (existing == null) {
                    candidates.save(SentryIssueCandidate.propose(issue, link.workspaceId, now))
                } else {
                    existing.proposeReopen(now)
                    candidates.save(existing)
                }
            }

            SentryIssueAction.OTHER -> return Result.SKIPPED
        }
        return Result.DONE
    }
}

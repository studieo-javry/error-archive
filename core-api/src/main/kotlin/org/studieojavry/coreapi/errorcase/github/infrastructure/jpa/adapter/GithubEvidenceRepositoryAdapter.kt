package org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.adapter

import org.springframework.data.domain.Limit
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.github.application.port.CaseGithubCleanupPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort
import org.studieojavry.coreapi.errorcase.github.application.port.StepGithubCleanupPort
import org.studieojavry.coreapi.errorcase.github.application.port.GithubEvidenceRepositoryPort.ClosingRefSource
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubEvent
import org.studieojavry.coreapi.errorcase.github.domain.model.CaseGithubLink
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubIssueSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.GithubPullRequestSnapshot
import org.studieojavry.coreapi.errorcase.github.domain.model.StepGithubLink
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.CiSummary
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubEventCursor
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubItemRef
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubRefKind
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.CaseGithubEventJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.CaseGithubLinkJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.CaseStepGithubLinkJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubIssueJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubPrIssueRefJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.GithubPullRequestJpaRepository
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.CaseGithubEventEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.CaseGithubLinkEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.CaseStepGithubLinkEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubIssueEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubItemKey
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubPrIssueRefEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.jpa.entity.GithubPullRequestEntity
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Component
class GithubEvidenceRepositoryAdapter(
    private val issues: GithubIssueJpaRepository,
    private val pullRequests: GithubPullRequestJpaRepository,
    private val prIssueRefs: GithubPrIssueRefJpaRepository,
    private val links: CaseGithubLinkJpaRepository,
    private val events: CaseGithubEventJpaRepository,
    private val stepLinks: CaseStepGithubLinkJpaRepository,
    private val objectMapper: ObjectMapper,
) : GithubEvidenceRepositoryPort, CaseGithubCleanupPort, StepGithubCleanupPort {

    // ── 스냅샷 ────────────────────────────────────────────────
    override fun findIssue(ref: GithubItemRef): GithubIssueSnapshot? =
        issues.findById(GithubItemKey(ref.repoId, ref.number)).orElse(null)?.toDomain()

    override fun findIssues(refs: Collection<GithubItemRef>): List<GithubIssueSnapshot> =
        if (refs.isEmpty()) emptyList()
        else issues.findAllById(refs.map { GithubItemKey(it.repoId, it.number) }).map { it.toDomain() }

    override fun saveIssue(issue: GithubIssueSnapshot) {
        issues.save(
            GithubIssueEntity(
                issue.repoId, issue.number, issue.title.take(300), issue.url, issue.state, issue.authorLogin,
                issue.createdAtGh, issue.closedAtGh, issue.sourceUpdatedAt, issue.deleted,
            ),
        )
    }

    override fun findPullRequest(ref: GithubItemRef): GithubPullRequestSnapshot? =
        pullRequests.findById(GithubItemKey(ref.repoId, ref.number)).orElse(null)?.toDomain()

    override fun findPullRequests(refs: Collection<GithubItemRef>): List<GithubPullRequestSnapshot> =
        if (refs.isEmpty()) emptyList()
        else pullRequests.findAllById(refs.map { GithubItemKey(it.repoId, it.number) }).map { it.toDomain() }

    override fun savePullRequest(pr: GithubPullRequestSnapshot) {
        val ci = pr.ci
        pullRequests.save(
            GithubPullRequestEntity(
                pr.repoId, pr.number, pr.title.take(300), pr.url, pr.state, pr.isDraft, pr.authorLogin,
                pr.headSha, pr.baseRef, pr.additions, pr.deletions, pr.changedFiles,
                pr.createdAtGh, pr.mergedAtGh, pr.closedAtGh, pr.sourceUpdatedAt,
                ci?.sha, ci?.conclusion?.name, ci?.total, ci?.passed,
                ci?.let { objectMapper.writeValueAsString(it.failedNames) }, ci?.completedAt,
            ),
        )
    }

    // ── 장부 ─────────────────────────────────────────────────
    override fun replaceClosingRefs(pr: GithubItemRef, issues: Collection<GithubItemRef>, source: ClosingRefSource) {
        val current = prIssueRefs.findAllByPrRepoIdAndPrNumber(pr.repoId, pr.number).filter { it.source == source.name }
        val next = issues.toSet()
        val gone = current.filter { GithubItemRef(it.issueRepoId, it.issueNumber) !in next }
        if (gone.isNotEmpty()) prIssueRefs.deleteAll(gone)
        val known = current.map { GithubItemRef(it.issueRepoId, it.issueNumber) }.toSet()
        val now = Instant.now()
        val added = next.filter { it !in known }
            .map { GithubPrIssueRefEntity(pr.repoId, pr.number, it.repoId, it.number, source.name, now) }
        if (added.isNotEmpty()) prIssueRefs.saveAll(added)
    }

    override fun findClosingIssuesOf(prs: Collection<GithubItemRef>): Map<GithubItemRef, List<GithubItemRef>> {
        if (prs.isEmpty()) return emptyMap()
        val wanted = prs.toSet()
        // (repo IN, number IN) 은 교차곱이라 넓게 읽힘 — 원하는 PR 만 다시 거른다
        return prIssueRefs.findAllByPrRepoIdInAndPrNumberIn(wanted.map { it.repoId }.toSet(), wanted.map { it.number }.toSet())
            .filter { GithubItemRef(it.prRepoId, it.prNumber) in wanted }
            .sortedBy { it.observedAt }
            .groupBy({ GithubItemRef(it.prRepoId, it.prNumber) }, { GithubItemRef(it.issueRepoId, it.issueNumber) })
            .mapValues { (_, issues) -> issues.distinct() }
    }

    // ── 연결 ─────────────────────────────────────────────────
    override fun findLink(caseId: Long, kind: GithubRefKind, ref: GithubItemRef): CaseGithubLink? =
        links.findByCaseIdAndKindAndRepoIdAndNumber(caseId, kind.name, ref.repoId, ref.number)?.toDomain()

    override fun findLinkById(linkId: Long): CaseGithubLink? = links.findById(linkId).orElse(null)?.toDomain()

    override fun findLinks(caseId: Long): List<CaseGithubLink> =
        links.findAllByCaseIdOrderByLinkedAt(caseId).map { it.toDomain() }

    override fun saveLink(link: CaseGithubLink): CaseGithubLink = links.save(
        CaseGithubLinkEntity(
            link.id, link.caseId, link.kind.name, link.repoId, link.number, link.linkSource.name,
            link.viaIssueRepoId, link.viaIssueNumber, link.linkedByUserId, link.linkedAt,
            link.detachedAt, link.detachedByUserId,
        ),
    ).toDomain()

    // ── 타임라인 ──────────────────────────────────────────────
    override fun recordEvent(event: CaseGithubEvent) {
        val data = if (event.data.isEmpty()) null else objectMapper.writeValueAsString(event.data)
        val now = Instant.now()
        if (event.replaceable) {
            events.upsert(event.caseId, event.type.name, event.kind.name, event.repoId, event.number, event.occurredAt, data, event.dedupeKey, now)
        } else {
            events.insertIfAbsent(event.caseId, event.type.name, event.kind.name, event.repoId, event.number, event.occurredAt, data, event.dedupeKey, now)
        }
    }

    override fun findEventsBefore(caseId: Long, before: GithubEventCursor?, limit: Int): List<CaseGithubEvent> =
        (before?.let { events.findOlderThan(caseId, it.occurredAt, it.id, Limit.of(limit)) }
            ?: events.findAllByCaseIdOrderByOccurredAtDescIdDesc(caseId, Limit.of(limit)))
            .map {
                CaseGithubEvent(
                    it.caseId, CaseGithubEvent.Type.valueOf(it.type), GithubRefKind.valueOf(it.kind), it.repoId, it.number,
                    it.occurredAt, it.data?.let { json -> objectMapper.readValue(json, MAP_TYPE) } ?: emptyMap(), it.dedupeKey,
                    id = it.id,
                )
            }

    override fun countEvents(caseId: Long): Long = events.countByCaseId(caseId)

    // ── Step ↔ 근거 꼬리표 ──────────────────────────────────────
    override fun findStepLinks(caseId: Long): List<StepGithubLink> =
        stepLinks.findAllByCaseIdOrderByCreatedAt(caseId).map {
            StepGithubLink(it.stepId, it.linkId, it.caseId, StepGithubLink.Source.valueOf(it.source), it.createdByUserId, it.createdAt)
        }

    override fun replaceStepLinks(caseId: Long, stepId: Long, linkIds: Collection<Long>, userId: Long, now: Instant) {
        val current = stepLinks.findAllByStepId(stepId)
        val next = linkIds.toSet()
        val gone = current.filter { it.linkId !in next }
        if (gone.isNotEmpty()) stepLinks.deleteAll(gone)
        val known = current.map { it.linkId }.toSet()
        val added = next.filter { it !in known }
            .map { CaseStepGithubLinkEntity(stepId, it, caseId, StepGithubLink.Source.MANUAL.name, userId, now) }
        if (added.isNotEmpty()) stepLinks.saveAll(added)
    }

    override fun deleteStepLinksByLinkId(linkId: Long) {
        stepLinks.deleteAllByLinkId(linkId)
    }

    @Transactional
    override fun deleteAllByStepId(stepId: Long) {
        stepLinks.deleteAllByStepId(stepId)
    }

    @Transactional
    override fun deleteAllByCaseId(caseId: Long) {
        stepLinks.deleteAllByCaseId(caseId)
        events.deleteAllByCaseId(caseId)
        links.deleteAllByCaseId(caseId)
    }

    // ── 매핑 ─────────────────────────────────────────────────
    private fun GithubIssueEntity.toDomain() = GithubIssueSnapshot(
        repoId, number, title, url, state, authorLogin, createdAtGh, closedAtGh, sourceUpdatedAt, deleted,
    )

    private fun GithubPullRequestEntity.toDomain() = GithubPullRequestSnapshot(
        repoId, number, title, url, state, isDraft, authorLogin, headSha, baseRef, additions, deletions, changedFiles,
        createdAtGh, mergedAtGh, closedAtGh, sourceUpdatedAt,
        ci = ciHeadSha?.let { sha ->
            CiSummary(
                sha = sha,
                conclusion = CiSummary.CiConclusion.valueOf(ciConclusion ?: CiSummary.CiConclusion.PENDING.name),
                total = ciTotal ?: 0,
                passed = ciPassed ?: 0,
                failedNames = ciFailedNames?.let { objectMapper.readValue(it, LIST_TYPE) } ?: emptyList(),
                completedAt = ciCompletedAt,
            )
        },
    )

    private fun CaseGithubLinkEntity.toDomain() = CaseGithubLink(
        id, caseId, GithubRefKind.valueOf(kind), repoId, number, CaseGithubLink.LinkSource.valueOf(linkSource),
        viaIssueRepoId, viaIssueNumber, linkedByUserId, linkedAt, detachedAt, detachedByUserId,
    )

    companion object {
        private val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}
        private val LIST_TYPE = object : TypeReference<List<String>>() {}
    }
}

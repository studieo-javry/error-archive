package org.studieojavry.coreapi.errorcase.sentry.application.port

import org.studieojavry.coreapi.errorcase.sentry.domain.model.SentryIssueCandidate

/** 받은 편지함 적재. 같은 event_key(본문 sha256) 가 이미 있으면 false(중복 전달 — 무시). */
interface SentryWebhookInboxPort {
    fun insertIfAbsent(delivery: InboundDelivery): Boolean

    data class InboundDelivery(
        val eventKey: String,
        val resource: String,
        val action: String?,
        val payload: String,
    )
}

/** Sentry 프로젝트 slug → 연결된 워크스페이스. 미연결이면 null → 처리 SKIP. */
interface SentryProjectLinkPort {
    fun findByProject(projectSlug: String): Link?

    data class Link(val workspaceId: Long, val createdByUserId: Long)
}

/** Sentry 이슈 후보 조회/저장. */
interface SentryIssueCandidateRepositoryPort {
    fun find(issueId: String): SentryIssueCandidate?
    fun save(candidate: SentryIssueCandidate)
}

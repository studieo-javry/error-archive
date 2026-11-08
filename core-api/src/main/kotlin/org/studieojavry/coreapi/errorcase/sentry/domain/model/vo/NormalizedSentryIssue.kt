package org.studieojavry.coreapi.errorcase.sentry.domain.model.vo

/**
 * Sentry 웹훅 payload 에서 **화이트리스트 필드만** 뽑아 정규화한 이슈.
 * user·request·headers·env 등 민감/동적 필드는 애초에 뽑지 않는다(= 추출로 마스킹).
 *
 * @param fingerprintKey 과거 Solution 매칭용 정규화 키 — `issueType|culprit` 에서 동적값 제거.
 */
data class NormalizedSentryIssue(
    val issueId: String,
    val shortId: String?,
    val projectSlug: String,
    val org: String?,
    val title: String,
    val culprit: String?,
    val issueType: String?,
    val level: String,
    val permalink: String?,
    val fingerprintKey: String,
)

/** 다룰 Sentry 이슈 액션. 그 외(resolved·ignored·assigned 등)는 처리하지 않는다. */
enum class SentryIssueAction {
    /** 신규 이슈 생성. */
    CREATED,

    /** regression — resolved → unresolved(재발). */
    REGRESSION,

    /** 관심 밖 액션(SKIP). */
    OTHER,
    ;

    companion object {
        fun from(action: String?): SentryIssueAction = when (action?.lowercase()) {
            "created" -> CREATED
            "unresolved" -> REGRESSION
            else -> OTHER
        }
    }
}

/** Case 후보 상태. */
enum class SentryCandidateStatus {
    /** 신규 감지 — 사용자 채택 대기. */
    PROPOSED,

    /** 사용자가 채택(Case 로 승격). */
    ACCEPTED,

    /** 사용자가 버림. */
    DISMISSED,

    /** 해결된 Case 가 재발 — 재오픈 제안. */
    REOPEN_PROPOSED,
}

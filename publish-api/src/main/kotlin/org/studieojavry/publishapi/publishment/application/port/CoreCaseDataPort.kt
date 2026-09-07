package org.studieojavry.publishapi.publishment.application.port

import java.time.LocalDateTime

/**
 * 🎯core-api `/internal/error-cases/{id}/full-data` 호출 추상화
 *
 * 권한 (case owner == caller) 검사는 core-api 측에서 수행 → 여기는 그대로 받음.
 * 404/403 은 별도 예외로 변환.
 */
interface CoreCaseDataPort {
    fun fetchFullData(caseId: Long): CoreCaseFullData?
}

data class CoreCaseFullData(
    val id: Long,
    val ownerUserId: Long,
    val title: String,
    val description: String?,
    val tags: List<String>,
    val status: String,
    val visibility: String,
    val createdAt: LocalDateTime,
    val occurredAt: LocalDateTime?,
    val snapshot: SnapshotData?,
    val steps: List<StepData>,
    val solutions: List<SolutionData>,
    val snippets: List<SnippetData>,
    val attachments: List<AttachmentData>,
) {
    data class SnapshotData(
        val exceptionClass: String?,
        val exceptionMessage: String?,
        val rawStackTrace: String?,
        val fingerprint: String?,
    )
    data class StepData(
        val id: Long, val orderIndex: Int, val title: String,
        val body: String?, val insight: String?,
        val status: String, val attemptType: String?,
        val createdAt: LocalDateTime,
    )
    data class SolutionData(
        val id: Long, val title: String, val stepIds: List<Long>, val createdAt: LocalDateTime,
    )
    data class SnippetData(
        val markerId: String, val title: String?, val language: String,
        val code: String, val filePathOrClass: String?, val caption: String?,
    )
    data class AttachmentData(
        val markerId: String, val fileName: String, val kind: String,
        val contentType: String?, val storageUrl: String?,
    )
}

class CoreCaseAccessDeniedException(message: String) : RuntimeException(message)
class CoreCaseNotFoundException(message: String) : RuntimeException(message)

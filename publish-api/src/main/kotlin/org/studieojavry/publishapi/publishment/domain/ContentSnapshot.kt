package org.studieojavry.publishapi.publishment.domain

import java.time.LocalDateTime

/**
 * publish 시점의 불변 스냅샷. 원본 case 가 나중에 바뀌어도 본 스냅샷은 보존.
 *
 * 사용자가 선별한 step / solution 만 담는다. 마스킹은 저장 시점에 한 번 적용
 * → 페이지/MD/PDF 렌더 시 추가 처리 없음.
 */
data class ContentSnapshot(
    val originalCaseId: Long,
    val originalCaseTitle: String,
    val originalCaseCreatedAt: LocalDateTime,
    val description: String?,
    val tags: List<String>,
    val steps: List<StepDoc>,
    val solutions: List<SolutionDoc>,
    val snapshot: ErrorSnapshotDoc?,
    /**
     * description(케이스 본문)에 `@snippet(x)`/`@attach(x)` 로 인라인 배치한 자산 (C1).
     * step 자산과 별개 — 과거엔 수집 안 돼 발행 시 소리 없이 사라졌다.
     * 기본값 emptyList: 이 필드가 없던 legacy jsonb row 역직렬화 호환.
     */
    val descriptionSnippets: List<SnippetDoc> = emptyList(),
    val descriptionAttachments: List<AttachmentRef> = emptyList(),
) {
    data class StepDoc(
        val originalStepId: Long,
        val order: Int,
        val title: String?,
        val body: String,
        val outcome: String?,        // "SUCCESS" | "FAILURE" | "PARTIAL" | null
        val occurredAt: LocalDateTime?,
        val durationMinutes: Int?,
        /** 코드 스니펫 본문(필요 시) — markerId 가 아니라 *내용 자체* 를 임베드 */
        val snippets: List<SnippetDoc>,
        val attachments: List<AttachmentRef>,
    )

    data class SolutionDoc(
        val originalSolutionId: Long,
        val title: String?,
        val body: String,
        val snippets: List<SnippetDoc>,
    )

    data class SnippetDoc(
        val markerId: String,
        val title: String?,
        val language: String,
        val code: String,
        val filePathOrClass: String?,
        val caption: String?,
    )

    data class AttachmentRef(
        val markerId: String,
        val fileName: String,
        val kind: String,                // IMAGE / TEXT / JSON / DOCUMENT / OTHER
        val contentType: String?,        // 안정 파일 라우트의 inline allowlist 판단용
        val storageUrl: String?,         // objectKey (렌더 시점 presign)
    )

    data class ErrorSnapshotDoc(
        val exceptionClass: String?,
        val exceptionMessage: String?,
        val rawStackTrace: String?,
        val fingerprint: String?,
    )
}

package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.response

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import org.studieojavry.coreapi.errorcase.attachment.domain.model.vo.AttachmentKind
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import java.time.Instant
import java.time.LocalDateTime

/**
 * 첨부 inlineReferenced 판정(#144): Case 본문 또는 임의 Step 본문에 @attach(marker) 가 있으면 인라인(갤러리 제외).
 * 아무 데도 없으면 false(관련 첨부 갤러리). Step 본문만 스캔 못 하던 중복 노출 버그를 막는다.
 */
class ErrorCaseDetailInlineReferenceTest {

    private fun att(markerId: String) = Attachment(
        markerId = markerId, title = null, caption = null,
        fileName = "$markerId.png", contentType = "image/png", size = 1,
        kind = AttachmentKind.IMAGE, objectKey = "attachments/a/$markerId",
        uploadedByUserId = 1, uploadedAt = Instant.now(), errorCaseId = 10,
    )

    private fun caseWith(description: String?, attachments: List<org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment>): ErrorCase {
        val now = LocalDateTime.now()
        return ErrorCase.reconstitute(
            id = 10, ownerUserId = 1, title = "t", project = null, snapshot = null,
            description = description, meta = Meta.create(workspaceId = null, severityCode = null),
            visibility = Visibility.PRIVATE, snippets = emptyList(), attachments = attachments,
            tags = emptyList(), status = ErrorCaseStatus.OPEN, occurredAt = null,
            createdAt = now, updatedAt = now,
        )
    }

    @Test
    fun `본문·Step 본문·미참조 각각의 inlineReferenced`() {
        val case = caseWith(
            description = "본문 설명 @attach(inBody) 참고",   // inBody 는 Case 본문에 배치
            attachments = listOf(att("inBody"), att("inStep"), att("loose")),
        )

        val res = ErrorCaseDetailResponse.from(
            case,
            attachmentUrls = { _ -> "url" to "downloadUrl" },
            stepBodies = listOf("디버깅 로그 스크린샷 @attach(inStep)"),  // inStep 은 Step 본문에만
        )

        val byId = res.attachments.associateBy { it.markerId }
        assertTrue(byId.getValue("inBody").inlineReferenced, "Case 본문 참조 → 인라인")
        assertTrue(byId.getValue("inStep").inlineReferenced, "Step 본문 참조 → 인라인(갤러리 제외)")
        assertFalse(byId.getValue("loose").inlineReferenced, "아무 데도 미참조 → 갤러리")
        assertEquals(3, res.attachments.size)
    }

    @Test
    fun `stepBodies 미전달이면 본문 기준만(하위호환)`() {
        val case = caseWith(
            description = "@attach(inBody)",
            attachments = listOf(att("inBody"), att("inStep")),
        )
        val res = ErrorCaseDetailResponse.from(case, attachmentUrls = { _ -> "u" to "d" })
        val byId = res.attachments.associateBy { it.markerId }
        assertTrue(byId.getValue("inBody").inlineReferenced)
        assertFalse(byId.getValue("inStep").inlineReferenced)
    }
}

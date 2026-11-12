package org.studieojavry.coreapi.errorcase.step.application.usecase

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import org.studieojavry.coreapi.errorcase.attachment.domain.model.vo.AttachmentKind
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import java.time.Instant

/**
 * Step 본문의 @attach/@snippet 마커가 이 Case 에 링크된 것만 참조하는지 검증(#144).
 * 링크 안 된/외부 마커 참조 시 422(Unknown*ReferenceException).
 */
class StepBodyMarkerValidationTest {

    private fun caseWithAttachment(markerId: String): ErrorCase {
        val att = Attachment(
            markerId = markerId, title = null, caption = null,
            fileName = "shot.png", contentType = "image/png", size = 10,
            kind = AttachmentKind.IMAGE, objectKey = "attachments/a/$markerId",
            uploadedByUserId = 1, uploadedAt = Instant.now(), errorCaseId = 10,
        )
        return ErrorCase.create(
            ownerUserId = 1, title = "t", project = null, snapshot = null, description = null,
            meta = Meta.create(workspaceId = null, severityCode = null),
            visibility = Visibility.PRIVATE, snippets = emptyList(), attachments = listOf(att),
            tags = emptyList(), occurredAt = null,
        )
    }

    @Test
    fun `링크된 첨부 마커 참조는 통과`() {
        val case = caseWithAttachment("att1")
        assertDoesNotThrow { validateStepBodyMarkers("설명 @attach(att1) 참고", case) }
    }

    @Test
    fun `본문 없음·마커 없음은 통과`() {
        val case = caseWithAttachment("att1")
        assertDoesNotThrow { validateStepBodyMarkers(null, case) }
        assertDoesNotThrow { validateStepBodyMarkers("마커 없는 본문", case) }
    }

    @Test
    fun `미링크 첨부 마커 참조는 422`() {
        val case = caseWithAttachment("att1")
        val ex = assertThrows<UnknownAttachmentReferenceException> {
            validateStepBodyMarkers("@attach(ghost) 는 이 케이스 소속 아님", case)
        }
        assertEquals("ghost", ex.markerId)
    }

    @Test
    fun `미링크 스니펫 마커 참조는 422`() {
        val case = caseWithAttachment("att1") // snippets 비어 있음
        val ex = assertThrows<UnknownSnippetReferenceException> {
            validateStepBodyMarkers("@snippet(s1) 참조", case)
        }
        assertEquals("s1", ex.markerId)
    }
}

package org.studieojavry.coreapi.errorcase.case

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import org.studieojavry.coreapi.errorcase.attachment.domain.model.vo.AttachmentKind
import org.studieojavry.coreapi.errorcase.case.application.command.UpdateErrorCaseCommand
import org.studieojavry.coreapi.errorcase.case.application.port.AuthorSummaryReaderPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.usecase.ErrorCaseLinkException
import org.studieojavry.coreapi.errorcase.case.application.usecase.UpdateErrorCaseUseCase
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.shared.application.port.FollowedUserReaderPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceMemberReaderPort
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort
import org.studieojavry.coreapi.errorcase.step.domain.model.Step
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.StepStatus
import java.time.Instant

/**
 * Case 수정 시, Step 본문이 아직 @attach 로 참조 중인 첨부 marker 는 unlink 금지(#144).
 * orphan GC 로 Step 토큰이 깨지는 것을 막는 "참조=항상 링크" 불변식. ErrorCaseLinkException(→400).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CaseAttachmentUnlinkGuardTest {

    @MockitoBean private lateinit var authorSummaryReader: AuthorSummaryReaderPort
    @MockitoBean private lateinit var workspaceMemberReader: WorkspaceMemberReaderPort
    @MockitoBean private lateinit var followedUserReader: FollowedUserReaderPort

    @Autowired private lateinit var update: UpdateErrorCaseUseCase
    @Autowired private lateinit var errorCases: ErrorCaseRepositoryPort
    @Autowired private lateinit var attachments: ErrorCaseAttachmentRepositoryPort
    @Autowired private lateinit var steps: StepRepositoryPort

    private val owner = 4242L

    private fun newCase(): Long {
        val c = errorCases.save(
            ErrorCase.create(
                ownerUserId = owner, title = "t", project = null, snapshot = null, description = null,
                meta = Meta.create(workspaceId = null, severityCode = null),
                visibility = Visibility.PRIVATE, snippets = emptyList(), attachments = emptyList(),
                tags = emptyList(), occurredAt = null,
            )
        )
        return c.id!!
    }

    private fun linkAttachment(markerId: String, caseId: Long) {
        attachments.save(
            Attachment(
                markerId = markerId, title = null, caption = null, fileName = "$markerId.png",
                contentType = "image/png", size = 1, kind = AttachmentKind.IMAGE,
                objectKey = "attachments/x/$markerId", uploadedByUserId = owner, uploadedAt = Instant.now(),
            )
        )
        attachments.linkToCase(listOf(markerId), caseId)
    }

    private fun addStep(caseId: Long, body: String) {
        steps.save(
            Step.create(
                errorCaseId = caseId, authorUserId = owner, orderIndex = 0,
                title = "repro", status = StepStatus.IN_PROGRESS, attemptType = null, body = body, insight = null,
            )
        )
    }

    private fun cmd(caseId: Long, attachmentMarkerIds: List<String>) = UpdateErrorCaseCommand(
        errorCaseId = caseId, requesterUserId = owner, title = null, project = null, paste = null,
        description = null, attachmentMarkerIds = attachmentMarkerIds,
    )

    @Test
    fun `Step 본문이 참조 중인 첨부를 unlink 하려 하면 ErrorCaseLinkException`() {
        val caseId = newCase()
        linkAttachment("mref", caseId)
        addStep(caseId, "로그 참고 @attach(mref)")

        // attachmentMarkerIds=[] → mref unlink 시도. Step 이 참조 중이라 거부.
        val ex = assertThrows<ErrorCaseLinkException> { update.invoke(cmd(caseId, emptyList())) }
        assertNotNull(ex.message)
        // 거부되었으므로 여전히 링크 유지.
        assertNotNull(attachments.findByMarkerId("mref")!!.errorCaseId)
    }

    @Test
    fun `어느 Step 도 참조하지 않는 첨부는 unlink 된다`() {
        val caseId = newCase()
        linkAttachment("mkeep", caseId)
        linkAttachment("mdrop", caseId)
        addStep(caseId, "로그 참고 @attach(mkeep)") // mdrop 은 아무도 참조 안 함

        // mkeep 유지, mdrop 해제 → mdrop 은 Step 미참조라 허용.
        assertDoesNotThrow { update.invoke(cmd(caseId, listOf("mkeep"))) }
        assertNull(attachments.findByMarkerId("mdrop")!!.errorCaseId)      // 해제됨
        assertNotNull(attachments.findByMarkerId("mkeep")!!.errorCaseId)   // 유지됨
    }
}

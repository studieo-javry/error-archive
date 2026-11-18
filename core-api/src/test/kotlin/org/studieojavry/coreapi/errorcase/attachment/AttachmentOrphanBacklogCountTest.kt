package org.studieojavry.coreapi.errorcase.attachment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import org.studieojavry.coreapi.errorcase.attachment.domain.model.vo.AttachmentKind
import org.studieojavry.coreapi.errorcase.shared.application.port.FollowedUserReaderPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceMemberReaderPort
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * orphan 백로그 게이지(AttachmentMetrics)가 쓰는 `countUnlinkedOlderThan` 검증.
 * Spring Data 파생쿼리(`countByErrorCaseIdIsNullAndUploadedAtBefore`)가 실제 Postgres 에서
 * 이름대로 파싱·실행되고, "미연결 + TTL 경과"만 세는지(최근/연결 건 제외) 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AttachmentOrphanBacklogCountTest {

    @MockitoBean private lateinit var workspaceMemberReader: WorkspaceMemberReaderPort
    @MockitoBean private lateinit var followedUserReader: FollowedUserReaderPort

    @Autowired private lateinit var attachments: ErrorCaseAttachmentRepositoryPort

    private fun unlinked(marker: String, uploadedAt: Instant): Attachment =
        attachments.save(
            Attachment(
                markerId = marker, title = null, caption = null, fileName = "f.txt",
                contentType = "text/plain", size = 1L, kind = AttachmentKind.TEXT,
                objectKey = "attachments/$marker", uploadedByUserId = 9001L, uploadedAt = uploadedAt,
            )
        )

    @Test
    fun `countUnlinkedOlderThan 은 미연결 + threshold 이전 건만 센다`() {
        val now = Instant.now()
        val old1 = now.minus(48, ChronoUnit.HOURS)
        val old2 = now.minus(30, ChronoUnit.HOURS)
        val recent = now.minus(1, ChronoUnit.HOURS)
        val threshold = now.minus(24, ChronoUnit.HOURS)

        unlinked("orphan-old-1", old1)
        unlinked("orphan-old-2", old2)
        unlinked("orphan-recent", recent) // TTL 미경과 → 제외

        // 연결된 건은 오래됐어도 제외
        val linked = unlinked("linked-old", old1)
        attachments.linkToCase(listOf(linked.markerId), errorCaseId = 777_001L)

        assertEquals(2L, attachments.countUnlinkedOlderThan(threshold))
    }
}

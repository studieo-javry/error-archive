package org.studieojavry.publishapi.demo

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.publishapi.publishment.application.port.CoreCaseDataPort
import org.studieojavry.publishapi.publishment.application.port.CoreCaseFullData
import org.studieojavry.publishapi.publishment.application.port.CasePublishmentRepositoryPort
import org.studieojavry.publishapi.publishment.application.usecase.CreatePublishmentUseCase
import org.studieojavry.publishapi.publishment.application.usecase.PublishInput
import org.studieojavry.publishapi.publishment.application.usecase.PublishmentNotResolvedException
import org.studieojavry.publishapi.publishment.domain.Visibility
import java.time.LocalDateTime

/**
 * 발행 정책(Option B): 공개(PUBLIC) 발행만 해결(RESOLVED/CLOSED) 케이스로 제한. UNLISTED 는 상태 무관 허용.
 *  - IN_PROGRESS + PUBLIC → PublishmentNotResolvedException (422), 저장 안 됨
 *  - IN_PROGRESS + UNLISTED → 정상 발행(진행 중 케이스 링크 공유)
 *  - RESOLVED/CLOSED + PUBLIC → 정상 발행
 * core 호출은 @Primary 페이크로 상태를 주입(실 HTTP 미사용).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ResolvedOnlyPublishTest {

    @TestConfiguration
    class FakeCoreConfig {
        @Bean @Primary
        fun fakeCore(): CoreCaseDataPort = object : CoreCaseDataPort {
            override fun fetchFullData(caseId: Long): CoreCaseFullData = fullData(
                caseId,
                status = when (caseId) {
                    IN_PROGRESS_CASE -> "IN_PROGRESS"
                    CLOSED_CASE -> "CLOSED"
                    else -> "RESOLVED"
                },
            )
            override fun fetchMyPersonalCaseIds(): List<Long> = emptyList()
        }
    }

    @Autowired private lateinit var createPublishment: CreatePublishmentUseCase
    @Autowired private lateinit var repository: CasePublishmentRepositoryPort

    @Test
    fun `미해결(IN_PROGRESS) 케이스의 PUBLIC 발행은 거부된다`() {
        assertThatThrownBy {
            createPublishment.invoke(501L, "k-inprogress-pub", "h", PublishInput(
                caseId = IN_PROGRESS_CASE, title = null, summary = null,
                visibility = Visibility.PUBLIC,
                includeStepIds = null, includeSolutionIds = null,
            ))
        }.isInstanceOf(PublishmentNotResolvedException::class.java)
        assertThat(repository.findByOriginalCaseId(IN_PROGRESS_CASE)).isNull()
    }

    @Test
    fun `미해결(IN_PROGRESS) 케이스도 UNLISTED 로는 발행된다`() {
        val r = createPublishment.invoke(501L, "k-inprogress-unlisted", "h", PublishInput(
            caseId = IN_PROGRESS_CASE, title = null, summary = null,
            visibility = Visibility.UNLISTED,
            includeStepIds = null, includeSolutionIds = null,
        ))
        assertThat(r.replayed).isFalse()
        assertThat(repository.findByOriginalCaseId(IN_PROGRESS_CASE)?.visibility).isEqualTo(Visibility.UNLISTED)
    }

    @Test
    fun `RESOLVED 케이스는 PUBLIC 으로 정상 발행된다`() {
        val r = createPublishment.invoke(501L, "k-resolved", "h", PublishInput(
            caseId = RESOLVED_CASE, title = null, summary = null,
            visibility = Visibility.PUBLIC,
            includeStepIds = null, includeSolutionIds = null,
        ))
        assertThat(r.replayed).isFalse()
        assertThat(repository.findByOriginalCaseId(RESOLVED_CASE)).isNotNull
    }

    @Test
    fun `CLOSED 케이스도 PUBLIC 으로 발행된다`() {
        createPublishment.invoke(501L, "k-closed", "h", PublishInput(
            caseId = CLOSED_CASE, title = null, summary = null,
            visibility = Visibility.PUBLIC,
            includeStepIds = null, includeSolutionIds = null,
        ))
        assertThat(repository.findByOriginalCaseId(CLOSED_CASE)).isNotNull
    }

    companion object {
        const val IN_PROGRESS_CASE = 7002L
        const val RESOLVED_CASE = 7001L
        const val CLOSED_CASE = 7003L

        fun fullData(caseId: Long, status: String) = CoreCaseFullData(
            id = caseId, ownerUserId = 501L, title = "케이스 $caseId",
            description = "본문", tags = listOf("demo"), status = status,
            visibility = "PRIVATE", createdAt = LocalDateTime.now(), occurredAt = null, snapshot = null,
            steps = listOf(
                CoreCaseFullData.StepData(
                    id = 1, orderIndex = 0, title = "조사", body = "로그 확인", insight = null,
                    status = "RESOLVED", attemptType = "INVESTIGATION", createdAt = LocalDateTime.now(),
                ),
            ),
            solutions = emptyList(), snippets = emptyList(), attachments = emptyList(),
        )
    }
}

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
import java.time.LocalDateTime

/**
 * 발행 정책: 해결된(RESOLVED) 케이스만 발행 가능.
 *  - IN_PROGRESS 등 미해결 → PublishmentNotResolvedException (컨트롤러 422), 저장 안 됨
 *  - RESOLVED → 정상 발행
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
            override fun fetchFullData(caseId: Long): CoreCaseFullData =
                fullData(caseId, status = if (caseId == IN_PROGRESS_CASE) "IN_PROGRESS" else "RESOLVED")
            override fun fetchMyPersonalCaseIds(): List<Long> = emptyList()
        }
    }

    @Autowired private lateinit var createPublishment: CreatePublishmentUseCase
    @Autowired private lateinit var repository: CasePublishmentRepositoryPort

    @Test
    fun `미해결(IN_PROGRESS) 케이스는 발행되지 않는다`() {
        assertThatThrownBy {
            createPublishment.invoke(501L, "k-inprogress", "h", PublishInput(
                caseId = IN_PROGRESS_CASE, title = null, summary = null,
                includeStepIds = null, includeSolutionIds = null,
            ))
        }.isInstanceOf(PublishmentNotResolvedException::class.java)
        assertThat(repository.findByOriginalCaseId(IN_PROGRESS_CASE)).isNull()
    }

    @Test
    fun `RESOLVED 케이스는 정상 발행된다`() {
        val r = createPublishment.invoke(501L, "k-resolved", "h", PublishInput(
            caseId = RESOLVED_CASE, title = null, summary = null,
            includeStepIds = null, includeSolutionIds = null,
        ))
        assertThat(r.replayed).isFalse()
        assertThat(repository.findByOriginalCaseId(RESOLVED_CASE)).isNotNull
    }

    companion object {
        const val IN_PROGRESS_CASE = 7002L
        const val RESOLVED_CASE = 7001L

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

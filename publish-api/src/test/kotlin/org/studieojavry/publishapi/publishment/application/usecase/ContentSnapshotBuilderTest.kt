package org.studieojavry.publishapi.publishment.application.usecase

import org.junit.jupiter.api.assertThrows
import org.studieojavry.publishapi.publishment.application.port.CoreCaseFullData
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ContentSnapshotBuilder — 발행 스냅샷 큐레이션 규칙 검증.
 * 핵심: step 최소 1개 강제, includeStepIds 필터, 본문 태그 마커만 자산화(방향 A),
 *       excluded 필터, description 태그 자산 수집(C1), step 간 duration 계산.
 */
class ContentSnapshotBuilderTest {

    private val t0 = LocalDateTime.of(2026, 1, 1, 9, 0)

    private fun step(id: Long, order: Int, body: String?, createdAt: LocalDateTime) =
        CoreCaseFullData.StepData(
            id = id, orderIndex = order, title = "step$id", body = body,
            insight = null, status = "SUCCESS", attemptType = null, createdAt = createdAt,
        )

    private fun full(
        steps: List<CoreCaseFullData.StepData>,
        description: String? = null,
        snippets: List<CoreCaseFullData.SnippetData> = emptyList(),
        attachments: List<CoreCaseFullData.AttachmentData> = emptyList(),
    ) = CoreCaseFullData(
        id = 100L, ownerUserId = 7L, title = "케이스", description = description,
        tags = listOf("kotlin"), status = "OPEN", visibility = "PUBLIC",
        createdAt = t0, occurredAt = null, snapshot = null,
        steps = steps, solutions = emptyList(), snippets = snippets, attachments = attachments,
    )

    private fun build(
        full: CoreCaseFullData,
        includeStepIds: Set<Long>? = null,
        excludedSnippetMarkerIds: Set<String> = emptySet(),
        excludedAttachmentMarkerIds: Set<String> = emptySet(),
    ) = ContentSnapshotBuilder.build(
        full = full,
        includeStepIds = includeStepIds,
        includeSolutionIds = null,
        excludedSnippetMarkerIds = excludedSnippetMarkerIds,
        excludedAttachmentMarkerIds = excludedAttachmentMarkerIds,
        options = PublishOptions.defaults(),
    )

    @Test
    fun `includeStepIds null 이면 모든 step 을 order 순으로 포함`() {
        val snap = build(full(listOf(
            step(2, 1, "b", t0.plusHours(1)),
            step(1, 0, "a", t0),
        )))
        assertEquals(listOf(0, 1), snap.steps.map { it.order })
        assertEquals(listOf(1L, 2L), snap.steps.map { it.originalStepId })
    }

    @Test
    fun `includeStepIds 로 부분 선별`() {
        val snap = build(
            full(listOf(step(1, 0, "a", t0), step(2, 1, "b", t0.plusHours(1)))),
            includeStepIds = setOf(2L),
        )
        assertEquals(listOf(2L), snap.steps.map { it.originalStepId })
    }

    @Test
    fun `선별된 step 이 0개면 PublishmentContentException (422 매핑)`() {
        assertThrows<PublishmentContentException> {
            build(full(listOf(step(1, 0, "a", t0))), includeStepIds = setOf(999L))
        }
        // 원본에 step 이 아예 없어도 동일
        assertThrows<PublishmentContentException> {
            build(full(emptyList()))
        }
    }

    @Test
    fun `본문에 태그된 마커만 자산화 — 미태그 스니펫은 제외 (방향 A)`() {
        val snap = build(full(
            steps = listOf(step(1, 0, "설명 @snippet(s1) 참고", t0)),
            snippets = listOf(
                CoreCaseFullData.SnippetData("s1", "used", "kotlin", "code1", null, null),
                CoreCaseFullData.SnippetData("s2", "unused", "kotlin", "code2", null, null),
            ),
        ))
        assertEquals(listOf("s1"), snap.steps[0].snippets.map { it.markerId })
    }

    @Test
    fun `excluded 마커는 태그돼 있어도 제외`() {
        val snap = build(
            full(
                steps = listOf(step(1, 0, "@attach(a1) @attach(a2)", t0)),
                attachments = listOf(
                    CoreCaseFullData.AttachmentData("a1", "f1.png", "IMAGE", "image/png", "k1"),
                    CoreCaseFullData.AttachmentData("a2", "f2.png", "IMAGE", "image/png", "k2"),
                ),
            ),
            excludedAttachmentMarkerIds = setOf("a2"),
        )
        assertEquals(listOf("a1"), snap.steps[0].attachments.map { it.markerId })
    }

    @Test
    fun `description 본문에 태그된 자산은 descriptionSnippets 로 수집 (C1)`() {
        val snap = build(full(
            steps = listOf(step(1, 0, "no markers", t0)),
            description = "케이스 개요 @snippet(d1)",
            snippets = listOf(CoreCaseFullData.SnippetData("d1", "desc", "java", "x", null, null)),
        ))
        assertEquals(listOf("d1"), snap.descriptionSnippets.map { it.markerId })
        assertTrue(snap.steps[0].snippets.isEmpty())
    }

    @Test
    fun `연속 step 사이 duration 은 createdAt 차이(분)로 계산`() {
        val snap = build(full(listOf(
            step(1, 0, "a", t0),
            step(2, 1, "b", t0.plusMinutes(90)),
        )))
        assertEquals(90, snap.steps[0].durationMinutes)
        assertEquals(null, snap.steps[1].durationMinutes) // 마지막 step 은 다음이 없어 null
    }
}

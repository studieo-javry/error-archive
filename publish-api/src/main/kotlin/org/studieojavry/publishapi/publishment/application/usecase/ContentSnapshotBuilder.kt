package org.studieojavry.publishapi.publishment.application.usecase

import org.studieojavry.publishapi.publishment.application.port.CoreCaseFullData
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import java.time.Duration

/**
 * core-api 의 풀 데이터 + 사용자 선별/옵션 → 불변 `ContentSnapshot` 생성.
 *
 *  - `includeStepIds = null` 이면 전체 step 포함
 *  - `excludedAttachmentMarkerIds`, `excludedSnippetMarkerIds` 로 제외 가능
 *  - 마스킹은 저장 시점에 한 번 적용 (description / step body / insight / solution title 등)
 *  - 스텝 사이 duration 은 createdAt 차이로 계산
 *
 * ── 첨부/스니펫 포함 정책 (방향 A: 본문 태그 기반 큐레이션) ─────────────────────────────
 *  발행물에는 step 본문에 `@attach(..)` / `@snippet(..)` 로 태그된 것만 포함한다. ([MARKERS_IN_BODY] 참고)
 *
 *  ⚠️ core-api 는 "업로드=보관, 태그=배치, 미태그=케이스 상세의 '관련 첨부/코드' 갤러리로 보존·노출"
 *     정책(2026-07-29, Attachment/Snippet `inlineReferenced`)을 쓴다. 즉 케이스 상세에서는 보이던
 *     미태그 첨부가 발행물에서는 빠진다 — 발행은 의도적 큐레이션이라는 전제(방향 A).
 *
 *  TODO(방향 B, 추후 변경 가능): 발행도 core-api 갤러리 모델과 일치시키려면
 *     (1) 여기서 마커 스캔 대신 `full.attachments` / `full.snippets` (케이스 연결 전체)를 후보로 올리고
 *     (2) 태그된 건 인라인, 미태그는 발행물 하단 "첨부/관련 코드" 섹션으로 배치하며
 *     (3) 발행 위저드의 include/exclude 후보 목록에도 미태그를 노출한다.
 *     현재는 (A) 유지 — 위저드에서 미태그는 "발행에 포함 안 됨"으로 안내하는 것으로 충분.
 *
 *  스캔 대상: step 본문 + 케이스 `description` 본문. description 태그 자산은
 *        StepDoc 이 아니라 스냅샷 최상위 `descriptionSnippets`/`descriptionAttachments` 로 결합되며
 *        Context 섹션에 렌더된다. excluded* 필터는 스텝과 동일 적용. B 로 갈 때 함께 재검토.
 * ─────────────────────────────────────────────────────────────────────────────────────
 */
object ContentSnapshotBuilder {

    fun build(
        full: CoreCaseFullData,
        includeStepIds: Set<Long>?,
        includeSolutionIds: Set<Long>?,
        excludedSnippetMarkerIds: Set<String>,
        excludedAttachmentMarkerIds: Set<String>,
        options: PublishOptions,
    ): ContentSnapshot {
        val stepsSorted = full.steps.sortedBy { it.orderIndex }
        val selectedSteps = if (includeStepIds == null) stepsSorted
        else stepsSorted.filter { it.id in includeStepIds }

        // 발행 정책: step 최소 1개. 원본 case 에 step 이 하나도 없거나(0개),
        // 사용자가 includeStepIds 로 전부 해제해 선별 결과가 비면 발행 불가.
        // Create / Update / Preview 세 경로가 모두 이 빌더를 지나므로 여기서 단일 강제.
        if (selectedSteps.isEmpty()) {
            throw PublishmentContentException(
                "발행하려면 최소 1개의 step 을 선택해야 합니다 (선별된 step 0개).",
            )
        }

        // duration = 다음 step.createdAt 과의 차이 (사용자 선별 step 사이만)
        val durations: Map<Long, Int?> = selectedSteps.mapIndexed { idx, s ->
            val next = selectedSteps.getOrNull(idx + 1)
            s.id to (next?.let {
                Duration.between(s.createdAt, it.createdAt).toMinutes().toInt().coerceAtLeast(0)
            })
        }.toMap()

        // 마커별 스니펫/첨부 인덱스
        val snippetByMarker = full.snippets.associateBy { it.markerId }
        val attachmentByMarker = full.attachments.associateBy { it.markerId }

        // step 본문에서 마커 발견 → 그 step 의 자식 자산으로 결합.
        // ★ 방향 A: 여기서 "본문에 태그된 마커"만 자산이 된다. 미태그 첨부/스니펫은 발행에서 제외됨.
        //   (전체 연결 자산을 싣는 방향 B 로 바꿀 경우 이 블록을 full.attachments/full.snippets 기반으로 교체 — 클래스 KDoc 참고)
        val stepDocs = selectedSteps.map { st ->
            val markersInBody = MARKERS_IN_BODY(st.body)
            val stepSnippets = resolveSnippets(markersInBody.snippetMarkers, excludedSnippetMarkerIds, snippetByMarker, options)
            val stepAttachments = resolveAttachments(markersInBody.attachMarkers, excludedAttachmentMarkerIds, attachmentByMarker)
            ContentSnapshot.StepDoc(
                originalStepId = st.id,
                order = st.orderIndex,
                title = options.applyMasking(st.title),
                body = options.applyMasking(st.body) ?: "",
                outcome = st.status,
                occurredAt = st.createdAt,
                durationMinutes = durations[st.id],
                snippets = stepSnippets,
                attachments = stepAttachments,
            )
        }

        // 솔루션 — selectedStep 포함 step만 참조해도 유지 (참조 정합성은 후처리 안 함, FE 가 알아서 무시)
        val solutionDocs = full.solutions
            .filter { includeSolutionIds == null || it.id in includeSolutionIds }
            .map { sol ->
                ContentSnapshot.SolutionDoc(
                    originalSolutionId = sol.id,
                    title = options.applyMasking(sol.title),
                    body = "",  // Solution 도메인은 body 가 없음 (title + stepIds 만)
                    snippets = emptyList(),
                )
            }

        // C1: description(케이스 본문)에 태그된 자산도 수집. 과거엔 스텝 본문만 스캔해
        //     description-전용 첨부/스니펫이 발행 시 소리 없이 사라졌다. excluded* 필터는 스텝과 동일 적용.
        val descMarkers = MARKERS_IN_BODY(full.description)
        val descriptionSnippets = resolveSnippets(descMarkers.snippetMarkers, excludedSnippetMarkerIds, snippetByMarker, options)
        val descriptionAttachments = resolveAttachments(descMarkers.attachMarkers, excludedAttachmentMarkerIds, attachmentByMarker)

        return ContentSnapshot(
            originalCaseId = full.id,
            originalCaseTitle = full.title,
            originalCaseCreatedAt = full.createdAt,
            description = options.applyMasking(full.description),
            tags = full.tags,
            steps = stepDocs,
            solutions = solutionDocs,
            descriptionSnippets = descriptionSnippets,
            descriptionAttachments = descriptionAttachments,
            snapshot = full.snapshot?.let {
                ContentSnapshot.ErrorSnapshotDoc(
                    exceptionClass = it.exceptionClass,
                    exceptionMessage = options.applyMasking(it.exceptionMessage),
                    rawStackTrace = options.applyMasking(it.rawStackTrace),
                    fingerprint = it.fingerprint,
                )
            },
        )
    }

    /** 마커 목록 → 스니펫 doc (excluded 제외, 존재하는 것만, 마스킹 적용). step·description 공용. */
    private fun resolveSnippets(
        markers: List<String>,
        excluded: Set<String>,
        index: Map<String, CoreCaseFullData.SnippetData>,
        options: PublishOptions,
    ): List<ContentSnapshot.SnippetDoc> =
        markers.filterNot { it in excluded }.mapNotNull { index[it] }.map { sn ->
            ContentSnapshot.SnippetDoc(
                markerId = sn.markerId,
                title = options.applyMasking(sn.title),
                language = sn.language,
                code = options.applyMasking(sn.code) ?: "",
                filePathOrClass = sn.filePathOrClass,
                caption = options.applyMasking(sn.caption),
            )
        }

    /** 마커 목록 → 첨부 ref (excluded 제외, 존재하는 것만). step·description 공용. */
    private fun resolveAttachments(
        markers: List<String>,
        excluded: Set<String>,
        index: Map<String, CoreCaseFullData.AttachmentData>,
    ): List<ContentSnapshot.AttachmentRef> =
        markers.filterNot { it in excluded }.mapNotNull { index[it] }.map { att ->
            ContentSnapshot.AttachmentRef(
                markerId = att.markerId,
                fileName = att.fileName,
                kind = att.kind,
                contentType = att.contentType,
                storageUrl = att.storageUrl,
            )
        }

    private data class FoundMarkers(
        val snippetMarkers: List<String>,
        val attachMarkers: List<String>,
    )

    private val SNIPPET_MARKER = Regex("@snippet\\(([A-Za-z0-9_-]+)\\)")
    private val ATTACH_MARKER = Regex("@attach\\(([A-Za-z0-9_-]+)\\)")

    @Suppress("FunctionName")
    private fun MARKERS_IN_BODY(body: String?): FoundMarkers {
        if (body.isNullOrEmpty()) return FoundMarkers(emptyList(), emptyList())
        return FoundMarkers(
            snippetMarkers = SNIPPET_MARKER.findAll(body).map { it.groupValues[1] }.distinct().toList(),
            attachMarkers = ATTACH_MARKER.findAll(body).map { it.groupValues[1] }.distinct().toList(),
        )
    }
}

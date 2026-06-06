package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

/**
 * 에러케이스 부분 수정(PATCH). 모든 필드 nullable — **보낸 필드만** 변경, null/미포함은 기존 값 유지.
 *
 * 스니펫·첨부는 **선언형 재연결**: marker 집합을 "최종 상태"로 보낸다.
 *  - 필드 미포함(null): 연결 그대로 유지.
 *  - 빈 배열([]): 전부 연결 해제.
 *  - [m1, m2]: 그 집합이 되도록 diff(추가/해제). (워크스페이스 이동, occurredAt 변경은 범위 밖)
 */
@Schema(description = "에러 케이스 부분 수정(PATCH). null/미포함 필드는 유지.")
data class UpdateErrorCaseRequest(
    @field:Schema(description = "제목", example = "(updated) NPE in OrderService", maxLength = 200)
    @field:Size(max = 200)
    val title: String?,

    @field:Schema(description = "프로젝트 식별자. null=유지.")
    val project: String?,

    @field:Schema(description = "새 paste — 보내면 스냅샷/지문 재추출")
    val paste: String?,

    @field:Schema(description = "본문(마크다운). `@snippet(...)`/`@attach(...)` 토큰 포함 가능")
    val description: String?,

    @field:Schema(description = "심각도 1..4", example = "3", minimum = "1", maximum = "4")
    @field:Min(1)
    @field:Max(4)
    val severity: Int?,

    // environment 는 자유 태그로 일반화됨 — POST/DELETE /tags 단건 endpoint 사용.

    @field:Schema(description = "최종 스니펫 marker 집합. **null=유지, []=전부 해제, [..]=그 집합으로 맞춤**", example = "[\"7a7d35e9\",\"b2468aca\"]")
    val snippetMarkerIds: List<String>? = null,

    @field:Schema(description = "최종 첨부 marker 집합. **null=유지, []=전부 해제, [..]=그 집합으로 맞춤**")
    val attachmentMarkerIds: List<String>? = null,

    @field:Schema(description = "상태 전이(예: RESOLVED). 도메인 전이 규칙(OPEN→IN_PROGRESS→RESOLVED→… )에 어긋나면 400.", example = "RESOLVED")
    val status: org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus? = null,

    @field:Schema(
        description = "가시성 변경. null=유지. 워크스페이스 케이스를 PUBLIC 으로 승격하려면 워크스페이스 ADMIN 필요. WORKSPACE 로 변경 시 workspaceId 필수.",
        example = "PUBLIC"
    )
    val visibility: org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility? = null,
)

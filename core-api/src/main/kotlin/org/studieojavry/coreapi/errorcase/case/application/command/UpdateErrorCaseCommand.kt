package org.studieojavry.coreapi.errorcase.case.application.command

/**
 * 에러케이스 부분 수정. null 필드는 "변경 안 함"(기존 값 유지).
 * 스니펫·첨부는 선언형 재연결: marker 집합 = 최종 상태. null=유지, []=전부 해제.
 * 워크스페이스 이동, occurredAt 변경은 이 명령의 범위가 아니다(후속).
 */
data class UpdateErrorCaseCommand(
    val errorCaseId: Long,
    val requesterUserId: Long,
    val title: String?,
    val scope: String?,
    val paste: String?,
    val description: String?,
    val severityCode: Int?,
    val environment: String?,
    val snippetMarkerIds: List<String>? = null,
    val attachmentMarkerIds: List<String>? = null,
    /** 상태 전이(예: IN_PROGRESS → RESOLVED). null=변경 없음. 도메인 transition 규칙 적용. */
    val status: org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus? = null,

    /**
     * 가시성 변경. null=유지. 워크스페이스 케이스를 PUBLIC 으로 승격하는 경우에만 워크스페이스 ADMIN 필요.
     * WORKSPACE 로 변경 시 workspaceId 가 null 이면 400 (도메인 invariant).
     */
    val visibility: org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility? = null,
)

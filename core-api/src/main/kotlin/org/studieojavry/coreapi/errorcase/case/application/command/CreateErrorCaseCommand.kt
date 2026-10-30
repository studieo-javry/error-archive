package org.studieojavry.coreapi.errorcase.case.application.command

import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import java.time.LocalDateTime

data class CreateErrorCaseCommand(
    val userId: Long,
    val title: String,
    val project: String?,
    val paste: String?,
    val description: String?,
    val snippetMarkerIds: List<String>,
    val attachmentMarkerIds: List<String>,
    val workspaceId: Long?,
    val tags: List<String>?,
    val occurredAt: LocalDateTime?,
    /** 가시성. 기본 PUBLIC. WORKSPACE 는 workspaceId 필수. workspaceId 있는 PUBLIC 은 워크스페이스 ADMIN 만. */
    val visibility: Visibility = Visibility.PUBLIC,
    /** 심각도 코드(1=S1 … 4=S4). null=미지정. AI 초안이 제안한 severity 를 이 통로로 저장한다. */
    val severityCode: Int? = null,
)

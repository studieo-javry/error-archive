package org.studieojavry.iamapi.workspace.presentation.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.iamapi.workspace.application.command.AcceptInvitationCommand
import org.studieojavry.iamapi.workspace.application.command.InviteMemberCommand
import org.studieojavry.iamapi.workspace.application.usecase.AcceptInvitationUseCase
import org.studieojavry.iamapi.workspace.application.usecase.InviteMemberUseCase
import org.studieojavry.iamapi.workspace.application.usecase.ListInvitationsUseCase
import org.studieojavry.iamapi.workspace.application.usecase.PreviewInvitationUseCase
import org.studieojavry.iamapi.workspace.application.usecase.RevokeInvitationUseCase
import org.studieojavry.iamapi.workspace.domain.model.vo.InvitationType
import org.studieojavry.iamapi.workspace.domain.model.vo.WorkspaceRole
import org.studieojavry.iamapi.workspace.presentation.web.dto.request.AcceptInvitationRequest
import org.studieojavry.iamapi.workspace.presentation.web.dto.request.CreateInvitationRequest
import org.studieojavry.iamapi.workspace.presentation.web.dto.response.AcceptInvitationResponse
import org.studieojavry.iamapi.workspace.presentation.web.dto.response.CreateInvitationResponse
import org.studieojavry.iamapi.workspace.presentation.web.dto.response.InvitationItemResponse
import org.studieojavry.iamapi.workspace.presentation.web.dto.response.InvitationPreviewResponse

@Tag(
    name = "workspace-invitations",
    description = "워크스페이스 초대 생성/조회/철회 (ADMIN 만), 토큰 미리보기(public), 수락(인증 사용자). EMAIL=일회용·이메일 발송, LINK=다회용."
)
@RestController
class WorkspaceInvitationController(
    private val inviteMemberUseCase: InviteMemberUseCase,
    private val listInvitationsUseCase: ListInvitationsUseCase,
    private val revokeInvitationUseCase: RevokeInvitationUseCase,
    private val acceptInvitationUseCase: AcceptInvitationUseCase,
    private val previewInvitationUseCase: PreviewInvitationUseCase
) {

    @Operation(
        summary = "초대 생성",
        description = """
            ADMIN 만. **type**:
             - `EMAIL` — 지정 이메일로 발송(SMTP). **일회용** (수락 시 PENDING→ACCEPTED 원자적 단일사용).
             - `LINK`  — URL 만 발급. **다회용** (만료까지 누구나 수락 가능).

            응답의 `inviteUrl` 을 공유(또는 EMAIL 의 경우 자동 발송).
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성됨(토큰/URL/만료시각)"),
        ApiResponse(responseCode = "400", description = "type/role/email 검증 실패", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "ADMIN 아님", content = [Content()])
    )
    @PostMapping("/api/v1/workspaces/{workspaceId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "워크스페이스 ID") @PathVariable workspaceId: Long,
        @Valid @RequestBody request: CreateInvitationRequest
    ): CreateInvitationResponse {
        val me = currentUserId(jwt)
        val r = inviteMemberUseCase.invoke(
            InviteMemberCommand(
                workspaceId = workspaceId,
                actorUserId = me,
                type = InvitationType.fromCode(request.type),
                email = request.email,
                role = WorkspaceRole.fromCode(request.role),
                expiresInHours = request.expiresInHours
            )
        )
        return CreateInvitationResponse(
            invitationId = r.invitationId,
            type = r.type.name,
            role = r.role.name,
            expiresAt = r.expiresAt,
            inviteToken = r.inviteToken,
            inviteUrl = r.inviteUrl
        )
    }

    @Operation(summary = "보류 중 초대 목록", description = "ADMIN 만. 아직 ACCEPTED 안 된 초대만 반환.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "ADMIN 아님", content = [Content()])
    )
    @GetMapping("/api/v1/workspaces/{workspaceId}/invitations")
    fun listPending(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "워크스페이스 ID") @PathVariable workspaceId: Long
    ): List<InvitationItemResponse> {
        val me = currentUserId(jwt)
        return listInvitationsUseCase.invoke(workspaceId, me).map {
            InvitationItemResponse(it.invitationId, it.type.name, it.email, it.role.name, it.expiresAt, it.createdAt)
        }
    }

    @Operation(summary = "초대 철회", description = "ADMIN 만. 이미 수락된 초대는 영향 없음.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "철회됨(멱등)"),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "403", description = "ADMIN 아님", content = [Content()])
    )
    @DeleteMapping("/api/v1/workspaces/{workspaceId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "워크스페이스 ID") @PathVariable workspaceId: Long,
        @Parameter(description = "초대 ID") @PathVariable invitationId: Long
    ) {
        val me = currentUserId(jwt)
        revokeInvitationUseCase.invoke(workspaceId, invitationId, me)
    }

    @Operation(
        summary = "초대 미리보기 (public)",
        description = """
            토큰만으로 워크스페이스 이름·역할·만료·사용 가능 여부를 미리 보여준다.
            **로그인 전 화면**에서 사용자가 가입/로그인 결정을 내릴 때 보여주는 용도. **인증 불필요**.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공(`usable=false`면 만료/사용됨/취소됨)"),
        ApiResponse(responseCode = "404", description = "토큰에 매칭되는 초대 없음", content = [Content()])
    )
    @SecurityRequirements
    @GetMapping("/api/v1/invitations/preview")
    fun preview(@Parameter(description = "초대 토큰") @RequestParam token: String): InvitationPreviewResponse {
        val r = previewInvitationUseCase.invoke(token)
        return InvitationPreviewResponse(
            workspaceId = r.workspaceId,
            workspaceName = r.workspaceName,
            role = r.role.name,
            expiresAt = r.expiresAt,
            usable = r.usable
        )
    }

    @Operation(
        summary = "초대 수락 → 멤버 가입",
        description = """
            **인증 필요**(가입/로그인 후 호출). 토큰을 capability 로 사용해 워크스페이스 멤버로 등록.
            EMAIL 타입은 **단일사용** — DB UPDATE 의 조건절(`status=PENDING`)이 잡힌 호출만 성공(원자적, 동시성 안전).
            LINK 타입은 만료 전이면 다회용으로 수락 가능.
        """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수락 완료(가입된 워크스페이스/역할 반환)"),
        ApiResponse(responseCode = "400", description = "토큰 잘못됨/만료/이미 사용됨/철회됨", content = [Content()]),
        ApiResponse(responseCode = "401", description = "인증 실패", content = [Content()]),
        ApiResponse(responseCode = "409", description = "이미 멤버", content = [Content()])
    )
    @PostMapping("/api/v1/invitations/accept")
    fun accept(
        @Parameter(hidden = true) @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: AcceptInvitationRequest
    ): AcceptInvitationResponse {
        val me = currentUserId(jwt)
        val r = acceptInvitationUseCase.invoke(AcceptInvitationCommand(token = request.token, acceptingUserId = me))
        return AcceptInvitationResponse(
            workspaceId = r.workspaceId,
            workspaceName = r.workspaceName,
            role = r.role.name
        )
    }

    private fun currentUserId(jwt: Jwt): Long =
        jwt.subject?.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid subject claim")
}

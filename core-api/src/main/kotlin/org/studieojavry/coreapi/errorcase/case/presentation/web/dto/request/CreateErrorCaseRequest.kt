package org.studieojavry.coreapi.errorcase.case.presentation.web.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Severity
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility


@Schema(description = "에러 케이스 생성 요청")
data class CreateErrorCaseRequest(
    @field:Schema(description = "케이스 제목", example = "NullPointerException in OrderService.calc", maxLength = 200, requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank
    @field:Size(max = 200)
    val title: String,

    @field:Schema(description = "스코프 식별자(현재는 자유 문자열; PERSONAL/WORKSPACE 등)", example = "PERSONAL", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank
    val scope: String,

    @field:Schema(description = "에러 원문(스택트레이스 포함). 서버가 클래스/메시지/스택/지문 추출.", example = "java.lang.NullPointerException at OrderService.calc(OrderService.kt:42)", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank
    val paste: String,

    @field:Schema(description = "본문(마크다운). `@snippet(markerId)`/`@attach(markerId)` 토큰으로 인라인 임베드", example = "원인은 캐시 만료 처리. @snippet(7a7d35e9) 참고.")
    val description: String?,

    @field:Schema(description = "연결할 스니펫 markerId 목록 (먼저 POST /error-snippets 로 생성)", example = "[\"7a7d35e9\"]")
    val snippetMarkerIds: List<String> = emptyList(),

    @field:Schema(description = "연결할 첨부 markerId 목록 (먼저 POST /error-attachments 로 업로드)", example = "[]")
    val attachmentMarkerIds: List<String> = emptyList(),

    @field:Schema(description = "워크스페이스 ID — 지정 시 WRITE+ 역할 필요. 미지정이면 개인 케이스", example = "1")
    val workspaceId: Long?,

    // Severity enum code 범위(S1=1 .. S4=4). enum 이 바뀌면 같이 갱신.
    @field:Schema(description = "심각도 1=S1(Outage), 2=S2(Degraded), 3=S3(Minor), 4=S4(Info)", example = "2", minimum = "1", maximum = "4")
    @field:Min(1)
    @field:Max(4)
    val severity: Int?,

    @field:Schema(description = "환경 식별자", example = "prod")
    val environment: String?,

    @field:Schema(description = "에러 발생 시각(미입력 시 null)", example = "2026-05-27T19:42:00")
    val occurredAt: LocalDateTime?,

    @field:Schema(
        description = "가시성. **기본 PUBLIC**(공유 자산화). WORKSPACE 는 workspaceId 필수. 워크스페이스 케이스를 PUBLIC 으로 만들려면 워크스페이스 ADMIN 만 가능.",
        example = "PUBLIC"
    )
    val visibility: Visibility = Visibility.PUBLIC,
)

package org.studieojavry.coreapi.errorcase.case.application.port

import org.springframework.stereotype.Repository

/**
 * 비정형 에러 로그를 LLM 으로 구조화하는 아웃바운드 포트.
 *
 * 비결정적 외부 호출이므로 실패(타임아웃·서킷오픈·JSON 파싱실패 등) 시 **null 을 반환**한다.
 * → UseCase 는 null 을 "LLM 미사용"으로 보고 정규식 기반으로 폴백한다(graceful degradation).
 *
 * 사실(exceptionClass/stacktrace/fingerprint)은 정규식(결정적)이 담당하고,
 * 이 포트는 title/summary/tags/severity 처럼 "해석"이 필요한 부분만 제안한다.
 */
@Repository
interface DraftExtractionPort {

    fun extract(input: Input): Output?

    data class Input(
        /** 민감정보 마스킹 + 길이 절삭이 끝난 로그. 원본을 그대로 외부로 보내지 않는다. */
        val maskedPaste: String,
        /** 정규식이 먼저 뽑은 예외 클래스(있으면 LLM 에 힌트로 전달). */
        val exceptionClassHint: String?,
        /** 이 워크스페이스에서 자주 쓰는 태그(신규 태그 남발 방지용 어휘). */
        val workspaceTags: List<String>,
    )

    data class Output(
        val title: String?,
        val exceptionClass: String?,
        val summary: String?,
        val tags: List<String>,
        /** "S1".."S4" | null — UseCase 가 enum 으로 검증한다(그 외 값은 버림). */
        val severity: String?,
        val confidence: Double,
    )
}

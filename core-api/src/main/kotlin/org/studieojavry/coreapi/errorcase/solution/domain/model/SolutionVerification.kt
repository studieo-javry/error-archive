package org.studieojavry.coreapi.errorcase.solution.domain.model

/**
 * Solution 에 보존하는 검증 근거. agentcapture 초안 반영(import) 시 후보 Solution 의 Verification 을
 * 그대로 옮겨 담는다. 저장소는 error_case_solution.verification(jsonb).
 *
 * agentcapture 도메인(Verification)과 직접 결합하지 않도록 errorcase 측 값 타입으로 분리한다.
 * 값이 없는 필드는 null — 직렬화 결과에서 어떻게 보이든(포함/생략) 의미는 동일하다.
 */
data class SolutionVerification(
    /** 검증 결과(PASSED/FAILED/NOT_RUN 등 코드명). */
    val result: String? = null,
    /** 검증 범위(코드명). */
    val scope: String? = null,
    /** 실행 명령 또는 테스트 러너. */
    val command: String? = null,
    /** 테스트 ID. */
    val testId: String? = null,
    /** AI 가 제안만 한 것이 아니라 실제 실행했는지. */
    val executed: Boolean = false,
    /** 실행 시각(ISO-8601 문자열, 원본 그대로 보존). */
    val ranAt: String? = null,
    /** 검증 시점 커밋 SHA. */
    val commitSha: String? = null,
    /** 자유 텍스트 근거(로그 발췌·결과 요약 등). */
    val evidence: String? = null,
)

package org.studieojavry.coreapi.agentcapture.domain.model

/**
 * Capture 초안의 서사 필드(사실·가설·판단 분리). 하나의 jsonb 블롭으로 저장한다.
 * 근거가 없으면 원인·검증·사용자 판단을 임의로 채우지 않는다(이슈 §4).
 *
 *  - observedFacts: 실행 로그·테스트 결과 등 실제 관측 사실
 *  - hypotheses:    AI 가 제시한 원인 후보
 *  - reproduction:  제안한 재현 절차 + 실제 실행 여부
 *  - environment:   관측 가능한 실행 환경
 *  - unknowns:      아직 확인하지 못한 내용
 */
data class CaptureContent(
    val symptom: String? = null,
    val observedFacts: List<String> = emptyList(),
    val hypotheses: List<String> = emptyList(),
    val reproduction: Reproduction? = null,
    val environment: CaptureEnvironment? = null,
    val unknowns: List<String> = emptyList(),
) {
    companion object {
        val EMPTY = CaptureContent()
    }
}

data class Reproduction(
    val steps: List<String> = emptyList(),
    /** 제안만 했는지(false), 실제 재현을 실행했는지(true). 근거 없이 성공 확정 금지. */
    val executed: Boolean = false,
)

data class CaptureEnvironment(
    val service: String? = null,
    val branch: String? = null,
    val commitSha: String? = null,
    val dirtyWorktree: Boolean? = null,
    /** 변경 diff 의 식별자(내용 자체가 아닌 참조). */
    val diffRef: String? = null,
)

/** Attempt 의 서사 필드(결과·순서·clientId 는 상위 컬럼). */
data class AttemptDetail(
    val title: String,
    val hypothesis: String? = null,
    /** 실제 수행한 내용. */
    val action: String? = null,
    /** 이 시도에서 얻은 판단/다음 단계. */
    val insight: String? = null,
    val nextStep: String? = null,
    /** 근거(첨부) 참조. P0 PR2 에선 비어 있고 PR3 에서 채운다. */
    val evidenceRefs: List<String> = emptyList(),
)

/** Solution 후보의 서사 필드(clientSolutionId·title 은 상위 컬럼). */
data class SolutionContent(
    val description: String? = null,
    /** 해결에 기여한 Attempt 참조(clientAttemptId). 최소 1개 존재하는 Attempt 를 가리켜야 한다. */
    val clientAttemptIds: List<String> = emptyList(),
    val verification: Verification? = null,
)

/** 검증 근거. PASSED 입력에는 실제 결과 근거가 필요하다(완전한 진실성은 서버가 보증하지 않음). */
data class Verification(
    val result: VerificationResult = VerificationResult.NOT_RUN,
    val scope: VerificationScope? = null,
    /** 실행 명령 또는 테스트 ID. AI 가 제안만 한 명령은 executed=false 로 구분. */
    val command: String? = null,
    val testId: String? = null,
    val executed: Boolean = false,
    val ranAt: String? = null,
    val commitSha: String? = null,
    val evidence: String? = null,
)

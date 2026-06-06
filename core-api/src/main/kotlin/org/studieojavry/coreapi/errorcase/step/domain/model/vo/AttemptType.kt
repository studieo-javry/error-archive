package org.studieojavry.coreapi.errorcase.step.domain.model.vo

/**
 * Step 의 시도 종류를 표현하는 **시스템 카탈로그**.
 *
 * `Step.attemptType` 자체는 자유 문자열(`String?`)로 저장된다 — 사용자 커스텀 값을 그대로 담을 수 있게.
 * 본 객체의 [SYSTEM] 상수는 *모든 사용자에게 기본 제공되는* 카탈로그 값이고, `GET /step-attempt-types`
 * 응답에서 `isSystem=true` 로 노출된다. 사용자별 커스텀은 별도 테이블 `step_attempt_type_custom` 에 저장.
 *
 * 새 system 값을 추가하면 클라이언트에서 자동으로 dropdown 에 노출됨 (DB 마이그레이션 불필요).
 *  - CODE_CHANGE   : 애플리케이션 코드 변경
 *  - CONFIG        : 설정 변경 (yml/env/feature flag 등)
 *  - DEPENDENCY    : 버전·라이브러리·SDK 변경
 *  - ENV           : 런타임/인프라 환경 변경 (배포 환경, OS, network)
 *  - ROLLBACK      : 이전 상태로 되돌림
 *  - INVESTIGATION : 원인 조사·로깅·재현 시도(직접 변경 없음)
 *  - OTHER         : 그 외
 */
object AttemptType {
    val SYSTEM: List<String> = listOf(
        "CODE_CHANGE",
        "CONFIG",
        "DEPENDENCY",
        "ENV",
        "ROLLBACK",
        "INVESTIGATION",
        "OTHER",
    )

    fun isSystem(name: String): Boolean = name in SYSTEM

    /** 정규화: trim · 빈 문자열 거부 · ≤64 자. null 반환 = 무시 (use case 가 null 처리). */
    fun normalize(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        require(v.length <= MAX_LENGTH) { "attemptType must be $MAX_LENGTH chars or less: $v" }
        return v
    }

    /** 동등성 비교용 키 (소문자) — 대소문자 다른 같은 의미 중복 방지. */
    fun normalizedKey(raw: String): String = raw.trim().lowercase()

    const val MAX_LENGTH = 64
}

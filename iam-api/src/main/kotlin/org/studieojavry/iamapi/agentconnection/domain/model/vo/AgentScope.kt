package org.studieojavry.iamapi.agentconnection.domain.model.vo

/**
 * agent 연결 토큰의 세분 권한. 로컬 커넥터(MCP)가 할 수 있는 일만 좁게 허용한다.
 *
 * [wire] 값이 토큰에 실려 downstream 으로 전달되는 표준 문자열(`scp` 클레임, SCOPE_* authority).
 * 검토 확정·Case 해결 확정·공개 발행·대량 삭제 권한은 **의도적으로 없다**(이슈 §3 정책).
 */
enum class AgentScope(val wire: String) {
    /** 허용 범위의 기존 사례 검색·조회(find_cases/get_case). */
    CASE_READ("case:read"),

    /** 내 초안 조회·재개(get_capture). */
    CAPTURE_READ("capture:read"),

    /** 초안 생성·시도/해결 후보 기록·봉인(start/record/propose/finish). */
    CAPTURE_WRITE("capture:write"),

    /** 근거 파일 업로드(attach_artifact). */
    ARTIFACT_WRITE("artifact:write"),
    ;

    companion object {
        private val byWire = AgentScope.entries.associateBy { it.wire }

        /** 기본 발급 범위 — P0 커넥터가 필요한 전부. */
        val DEFAULTS: Set<AgentScope> = setOf(CASE_READ, CAPTURE_READ, CAPTURE_WRITE, ARTIFACT_WRITE)

        fun fromWireOrNull(wire: String): AgentScope? = byWire[wire.trim()]

        /** DB/claim 저장용 CSV(정렬 — 결정적). */
        fun toCsv(scopes: Set<AgentScope>): String =
            scopes.sortedBy { it.wire }.joinToString(",") { it.wire }

        /** CSV 역직렬화 — 알 수 없는 토큰은 조용히 버린다(스키마 진화 시 forward-compat). */
        fun parseCsv(csv: String): Set<AgentScope> =
            csv.split(',')
                .mapNotNull { it.takeIf(String::isNotBlank)?.let(::fromWireOrNull) }
                .toSet()
    }
}

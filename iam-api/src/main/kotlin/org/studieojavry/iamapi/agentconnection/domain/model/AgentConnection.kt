package org.studieojavry.iamapi.agentconnection.domain.model

import org.studieojavry.iamapi.agentconnection.domain.model.vo.AgentScope
import java.time.Instant
import java.util.UUID

/**
 * agent 연결 토큰 1건. 로컬 커넥터(Claude Code MCP)가 agent 전용 경로를 호출할 때 제시하는
 * opaque 토큰의 서버측 기록이다. refresh token 과 같은 해시-저장 패턴을 따른다.
 *
 *  - [connectionId] 는 외부에 노출되는 안정적 식별자(UUID). 멱등 키 네임스페이스·`cid` 클레임에 쓰인다.
 *    토큰 원문을 교체(재발급)해도 같은 연결이면 유지하는 개념(refresh token 의 familyId 대응).
 *  - 토큰 원문은 저장하지 않는다. [tokenHash](sha256) 만 저장하고 조회는 해시로 한다.
 *  - 폐기는 삭제가 아니라 [revokedAt] 스탬프 — 감사 보존. 폐기/만료는 매 요청 introspection 에서 검사.
 */
class AgentConnection private constructor(
    val id: Long?,
    val ownerUserId: Long,
    val connectionId: UUID,
    val tokenHash: String,
    val scopes: Set<AgentScope>,
    val label: String?,
    val expiresAt: Instant,
    val createdAt: Instant,
    var revokedAt: Instant?,
    var lastUsedAt: Instant?,
    // ── 설치 환경 메타(표시용, 커넥터가 first-use 에 신고 — 인가엔 쓰지 않는다) ──
    var clientKind: String?,
    var clientVersion: String?,
    var installId: String?,
    var installLabel: String?,
    var clientBoundAt: Instant?,
) {
    init {
        require(tokenHash.isNotBlank()) { "tokenHash must not be blank" }
        require(scopes.isNotEmpty()) { "agent connection must grant at least one scope" }
    }

    val isRevoked: Boolean get() = revokedAt != null

    fun isExpired(now: Instant = Instant.now()): Boolean = !now.isBefore(expiresAt)

    /** introspection 이 매 요청 호출. 폐기/만료 어느 쪽이든 사용 불가. */
    fun isUsable(now: Instant = Instant.now()): Boolean = !isRevoked && !isExpired(now)

    fun hasScope(scope: AgentScope): Boolean = scope in scopes

    fun revoke(now: Instant = Instant.now()) {
        if (revokedAt != null) return
        this.revokedAt = now
    }

    fun touchLastUsed(at: Instant = Instant.now()) {
        this.lastUsedAt = at
    }

    /** 설치 메타가 아직 없는가(first-use 바인딩 대상). */
    val isClientBound: Boolean get() = clientBoundAt != null

    /**
     * 커넥터가 신고한 설치 메타를 기록한다(표시용). **멱등** — 최초 1회만 전체를 기록(clientBoundAt 스탬프),
     * 이후 호출은 clientVersion 만 갱신(도구 업데이트 반영)하고 설치 식별자는 바꾸지 않는다.
     * 어느 입력도 인가 판단에 쓰지 않는다(위조 가능한 자기신고 값).
     */
    fun bindClient(
        kind: String?,
        version: String?,
        installId: String?,
        installLabel: String?,
        now: Instant = Instant.now(),
    ): Boolean {
        val k = kind?.takeIf { it.isNotBlank() }
        val v = version?.takeIf { it.isNotBlank() }
        val iid = installId?.takeIf { it.isNotBlank() }
        val ilabel = installLabel?.takeIf { it.isNotBlank() }
        if (k == null && v == null && iid == null && ilabel == null) return false // 신고 없음
        if (clientBoundAt == null) {
            this.clientKind = k
            this.clientVersion = v
            this.installId = iid
            this.installLabel = ilabel
            this.clientBoundAt = now
            return true
        }
        // 이미 bound — 버전만 바뀌면 갱신(식별자·라벨은 고정)
        if (v != null && v != this.clientVersion) {
            this.clientVersion = v
            return true
        }
        return false
    }

    /** 목록 표시 이름: 웹 지정 label → 설치 라벨(hostname) → "{kind} · {cid 앞 8자}". */
    fun displayName(): String =
        label?.takeIf { it.isNotBlank() }
            ?: installLabel?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(clientKind?.takeIf { it.isNotBlank() }, connectionId.toString().take(8))
                .joinToString(" · ")

    companion object {
        fun issue(
            ownerUserId: Long,
            connectionId: UUID,
            tokenHash: String,
            scopes: Set<AgentScope>,
            label: String?,
            expiresAt: Instant,
            now: Instant = Instant.now(),
        ): AgentConnection = AgentConnection(
            id = null,
            ownerUserId = ownerUserId,
            connectionId = connectionId,
            tokenHash = tokenHash,
            scopes = scopes,
            label = label?.takeIf { it.isNotBlank() },
            expiresAt = expiresAt,
            createdAt = now,
            revokedAt = null,
            lastUsedAt = null,
            clientKind = null,
            clientVersion = null,
            installId = null,
            installLabel = null,
            clientBoundAt = null,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: Long,
            ownerUserId: Long,
            connectionId: UUID,
            tokenHash: String,
            scopes: Set<AgentScope>,
            label: String?,
            expiresAt: Instant,
            createdAt: Instant,
            revokedAt: Instant?,
            lastUsedAt: Instant?,
            clientKind: String? = null,
            clientVersion: String? = null,
            installId: String? = null,
            installLabel: String? = null,
            clientBoundAt: Instant? = null,
        ): AgentConnection = AgentConnection(
            id, ownerUserId, connectionId, tokenHash, scopes, label,
            expiresAt, createdAt, revokedAt, lastUsedAt,
            clientKind, clientVersion, installId, installLabel, clientBoundAt,
        )
    }
}

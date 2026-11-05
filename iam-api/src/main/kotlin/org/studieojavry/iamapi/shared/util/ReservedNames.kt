package org.studieojavry.iamapi.shared.util

/**
 * 데모 전용 이름 — 회원 handle·워크스페이스 slug 로 쓸 수 없다.
 * 데모 봇·페르소나·공유 게스트(`demo-*`)와 데모 워크스페이스(`demo`)는 이름으로 찾아지므로, 회원이 먼저
 * 차지하면 데모로 오인돼 야간 리셋이 그 워크스페이스 케이스를 지우거나 게스트가 회원 계정으로 로그인된다.
 */
object ReservedNames {

    private const val DEMO = "demo"

    fun isReserved(name: String): Boolean {
        val n = name.lowercase()
        return n == DEMO || n.startsWith("$DEMO-")
    }
}

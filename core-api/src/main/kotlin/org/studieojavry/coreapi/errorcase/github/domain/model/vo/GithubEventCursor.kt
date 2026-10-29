package org.studieojavry.coreapi.errorcase.github.domain.model.vo

import java.time.Instant
import java.util.Base64

/**
 * 근거 타임라인 커서 — 마지막으로 받은 이벤트의 (occurredAt, id). 다음 쪽은 이보다 **오래된** 것.
 * 클라이언트에는 뜻 없는 문자열로만 준다(형식이 바뀌어도 API 는 그대로).
 */
data class GithubEventCursor(val occurredAt: Instant, val id: Long) {
    fun encode(): String = ENCODER.encodeToString("$occurredAt|$id".toByteArray())

    companion object {
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        /** 형식이 틀리면 null. */
        fun decode(raw: String): GithubEventCursor? = runCatching {
            val (at, id) = String(Base64.getUrlDecoder().decode(raw)).split('|').also { require(it.size == 2) }
            GithubEventCursor(Instant.parse(at), id.toLong())
        }.getOrNull()
    }
}

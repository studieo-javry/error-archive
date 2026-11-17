package org.studieojavry.iamapi.agentconnection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.studieojavry.iamapi.agentconnection.config.AgentConnectorProperties
import org.studieojavry.iamapi.agentconnection.infrastructure.npm.NpmConnectorVersionFetcher

/** npm 응답(octet-stream 가능)을 String 으로 받아 파싱하는 로직 검증 — content-type 무관. */
class NpmConnectorVersionFetcherParseTest {
    private val fetcher = NpmConnectorVersionFetcher(AgentConnectorProperties())

    @Test
    fun `실제 npm packument 형태에서 dist-tags latest 추출`() {
        val json =
            """
            {"_id":"@error-archive/agent","name":"@error-archive/agent",
             "dist-tags":{"latest":"1.11.3","next":"1.12.0-beta.1"},
             "versions":{"1.11.3":{"name":"@error-archive/agent","version":"1.11.3"}},
             "time":{"modified":"2026-11-17T00:00:00.000Z"}}
            """.trimIndent()
        assertEquals("1.11.3", fetcher.parseLatest(json))
    }

    @Test
    fun `dist-tags 누락이면 null`() {
        assertNull(fetcher.parseLatest("""{"name":"x","versions":{}}"""))
    }

    @Test
    fun `빈 입력이면 null`() {
        assertNull(fetcher.parseLatest(null))
        assertNull(fetcher.parseLatest(""))
        assertNull(fetcher.parseLatest("   "))
    }
}

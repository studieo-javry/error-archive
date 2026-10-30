package org.studieojavry.coreapi.errorcase.github

import org.junit.jupiter.api.Test
import org.studieojavry.coreapi.errorcase.github.infrastructure.api.GithubAppApiAdapter
import kotlin.test.assertEquals

/** `#` 검색어 → GitHub 검색 질의. 사용자 입력이 한정자로 범위를 넓히지 못해야 한다. */
class GithubReferenceSearchQueryTest {

    private val repos = listOf("acme/payment-api", "acme/payment-infra")

    @Test
    fun `빈 검색어는 레포 한정 + 최근 갱신순`() {
        assertEquals("repo:acme/payment-api repo:acme/payment-infra sort:updated-desc", GithubAppApiAdapter.searchQuery(repos, ""))
    }

    @Test
    fun `글자는 제목 검색`() {
        assertEquals("repo:acme/payment-api repo:acme/payment-infra 결제 in:title sort:updated-desc", GithubAppApiAdapter.searchQuery(repos, "결제"))
    }

    @Test
    fun `한정자·따옴표는 지운다`() {
        assertEquals(
            "repo:acme/payment-api 결제 in:title sort:updated-desc",
            GithubAppApiAdapter.searchQuery(listOf("acme/payment-api"), "repo:evil/secret \"결제 is:open"),
        )
    }
}

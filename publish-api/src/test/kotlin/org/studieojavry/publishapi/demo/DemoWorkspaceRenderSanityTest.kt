package org.studieojavry.publishapi.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.publishapi.demo.application.DemoWorkspacePublishmentSeeder
import org.studieojavry.publishapi.demo.application.DemoWorkspacePublishmentSeeder.Companion.WS_ORIGIN_BASE
import org.studieojavry.publishapi.publishment.application.port.CasePublishmentRepositoryPort
import org.studieojavry.publishapi.publishment.application.usecase.HtmlRenderer
import java.nio.file.Files
import java.nio.file.Path

/**
 * 시드 콘텐츠가 공개 페이지 HTML 로 "풍성하게" 렌더되는지 실제 렌더 검증.
 * 샘플 HTML 을 임시 파일로 덤프(구조 눈검사용).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DemoWorkspaceRenderSanityTest {

    @Autowired private lateinit var seeder: DemoWorkspacePublishmentSeeder
    @Autowired private lateinit var repository: CasePublishmentRepositoryPort

    @Test
    fun `시드 발행물이 코드블록·FAILED스텝·에러터미널·솔루션을 갖춘 HTML 로 렌더된다`() {
        seeder.seed()
        val p = repository.findByOriginalCaseId(WS_ORIGIN_BASE + 1)!!   // HikariCP 케이스
        val html = HtmlRenderer.render(p, forPdf = false, publicBaseUrl = "https://p.error-archive.com")

        // 구조/풍성도
        assertThat(html).contains("Resolved")                 // Resolved 배지
        assertThat(html).contains("Chasing the Cause")        // The Journey 섹션
        assertThat(html).contains("Failed")                   // FAILED 스텝 워드
        assertThat(html).contains("The Resolution")           // 솔루션 섹션
        assertThat(html).contains("What Broke")               // 에러 터미널 섹션
        assertThat(html).contains("HikariPool-1")             // 스택트레이스 내용
        assertThat(html).contains("class=\"code-bar\"")       // 코드블록(스니펫/펜스)
        assertThat(html).contains("김도현")                    // authorDisplayName

        // 렌더 아티팩트 없음: 리터럴 강조 별표가 본문에 노출되면 안 됨
        // (code-bar/pre 내부가 아닌 prose 영역) — 전체에서 '**' 쌍이 없어야 함.
        assertThat(html).doesNotContain("**")

        // 눈검사용 덤프
        val out = Path.of(System.getProperty("java.io.tmpdir"), "demo-publishment-sample.html")
        Files.writeString(out, html)
        println("[render-sanity] html=${html.length} chars → $out")
    }
}

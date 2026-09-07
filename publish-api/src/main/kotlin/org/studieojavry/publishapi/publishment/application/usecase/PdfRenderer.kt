package org.studieojavry.publishapi.publishment.application.usecase

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.helper.W3CDom
import org.springframework.stereotype.Component
import org.studieojavry.publishapi.publishment.domain.CasePublishment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * HTML → PDF — OpenHTMLToPdf 사용. jsoup 으로 HTML 을 XHTML 로 정규화.
 *
 * 한글 폰트: 클래스패스 `fonts/NotoSansKR-Regular.ttf` 가 있으면 임베드.
 * (없으면 기본 폰트 — 한글 □□□ 될 수 있음. 운영 전 추가 필수)
 */
@Component
class PdfRenderer {

    private val log = KotlinLogging.logger {}

    /** 한글 폰트 bytes — 클래스 로드 시 1회 읽어 캐시. useFont 의 supplier 가 매 호출마다 새 stream 반환해야 하므로. */
    private val fontBytes: ByteArray? by lazy {
        javaClass.getResourceAsStream("/fonts/NotoSansKR-Regular.ttf")?.use { it.readBytes() }
            .also {
                if (it == null) log.warn { "Korean font not found at /fonts/NotoSansKR-Regular.ttf — Korean glyphs may render as □" }
                else log.info { "Korean font loaded: ${it.size} bytes" }
            }
    }

    fun render(
        p: CasePublishment,
        publicBaseUrl: String = "",
        imageEmbedUrl: (String) -> String? = { null },
    ): ByteArray {
        val html = HtmlRenderer.render(p, forPdf = true, publicBaseUrl = publicBaseUrl, imageEmbedUrl = imageEmbedUrl)
        val xhtml = jsoupToXhtml(html)
        val baos = ByteArrayOutputStream()
        try {
            val builder = PdfRendererBuilder()
            // 한글 폰트 임베드 — supplier 가 매번 새 InputStream 반환해야 함
            fontBytes?.let { bytes ->
                builder.useFont({ ByteArrayInputStream(bytes) }, "Noto Sans KR")
            }
            builder.withW3cDocument(W3CDom().fromJsoup(Jsoup.parse(xhtml)), "/")
            builder.toStream(baos)
            builder.run()
        } catch (e: Exception) {
            log.error(e) { "PDF render failed for slug=${p.slug}" }
            throw e
        }
        return baos.toByteArray()
    }

    /** OpenHTMLToPdf 는 *valid XHTML* 만 받음. jsoup 의 parser 가 닫힘 태그 자동 보정. */
    private fun jsoupToXhtml(html: String): String {
        val doc = Jsoup.parse(html)
        doc.outputSettings(
            org.jsoup.nodes.Document.OutputSettings()
                .syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
                .charset("UTF-8")
        )
        return doc.outerHtml()
    }
}

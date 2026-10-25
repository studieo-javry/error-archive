package org.studieojavry.publishapi.publishment.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.studieojavry.publishapi.publishment.application.usecase.AttachmentRender
import org.studieojavry.publishapi.publishment.application.usecase.GetPublicPageMetaUseCase
import org.studieojavry.publishapi.publishment.application.usecase.GetPublishmentBySlugUseCase
import org.studieojavry.publishapi.publishment.application.usecase.ListPublicPublishmentsUseCase
import org.studieojavry.publishapi.publishment.application.usecase.PublicPageRenderCache
import org.studieojavry.publishapi.publishment.application.usecase.PdfRenderer
import org.studieojavry.publishapi.publishment.application.usecase.PublishmentForbiddenException
import org.studieojavry.publishapi.publishment.application.usecase.PublishmentGoneException
import org.studieojavry.publishapi.publishment.application.usecase.PublishmentNotFoundException
import org.studieojavry.publishapi.publishment.application.usecase.RecordPublishmentDownloadUseCase
import org.studieojavry.publishapi.publishment.application.usecase.RecordPublishmentViewUseCase
import org.studieojavry.publishapi.publishment.infrastructure.attachment.AttachmentPresigner
import org.studieojavry.publishapi.shared.config.PagePublicProperties
import java.time.Duration

/**
 * 공개 페이지 — 인증 X.
 *  - `GET /p/{slug}`            HTML 페이지 (브라우저에서 바로)
 *  - `GET /p/{slug}.pdf`        PDF 다운로드
 */
@Tag(name = "publishments-public", description = "공개 페이지 — 인증 불필요.")
@RestController
class PublicPageController(
    private val getBySlugUseCase: GetPublishmentBySlugUseCase,
    private val recordViewUseCase: RecordPublishmentViewUseCase,
    private val recordDownloadUseCase: RecordPublishmentDownloadUseCase,
    private val pdfRenderer: PdfRenderer,
    private val attachmentPresigner: AttachmentPresigner,
    private val pageProps: PagePublicProperties,
    private val listPublicUseCase: ListPublicPublishmentsUseCase,
    private val renderCache: PublicPageRenderCache,
    private val metaUseCase: GetPublicPageMetaUseCase,
) {
    /** 첨부 objectKey → presigned inline URL (PDF 이미지 임베드용). 웹/PDF 링크는 안정 라우트. */
    private val imageEmbedUrl: (String) -> String? = { attachmentPresigner.viewUrl(it) }

    @Operation(summary = "공개 페이지 (HTML)")
    @GetMapping("/p/{slug}", produces = [MediaType.TEXT_HTML_VALUE])
    fun viewPage(
        @Parameter(description = "slug") @PathVariable slug: String,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        // 경량 메타 조회로 게이팅 + 캐시 key(updatedAt) 확보 — content(jsonb) 역직렬화 없음.
        val meta = lookupMeta(slug)
        val builder = ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8")
        // 조회수 집계 — 공개 페이지에서만 + 쿠키 dedup(방문자당 이 slug 24h 1회).
        // 쿠키(Path=/p/{slug}) 가 있으면 skip, 없으면 +1 후 24h 쿠키 발급.
        val cookieName = viewCookieName(slug)
        val alreadyViewed = request.cookies?.any { it.name == cookieName } == true
        if (!alreadyViewed) {
            recordViewUseCase.invoke(slug)
            val cookie = ResponseCookie.from(cookieName, "1")
                .maxAge(Duration.ofHours(24))
                .path("/p/$slug")
                .httpOnly(true)
                .sameSite("Lax")
                .build()
            builder.header(HttpHeaders.SET_COOKIE, cookie.toString())
        }
        // 렌더는 캐시 경유(key = slug@updatedAt). HIT 시 전체 로드+렌더를 건너뜀(캐시 MISS 안에서만 수행).
        // 위 조회수/쿠키 로직은 캐시 밖이라 view_count 정확 유지.
        return builder.body(renderCache.render(slug, meta.updatedAt.toString()))
    }

    /** 쿠키 이름 — slug 는 `[a-z0-9-]` 라 토큰 안전. `.` 등 치환 방어용으로 sanitize. */
    private fun viewCookieName(slug: String): String = "pv_" + slug.replace(Regex("[^a-zA-Z0-9-]"), "_")

    @Operation(summary = "공개 페이지 PDF 다운로드")
    @GetMapping("/p/{slug}.pdf")
    fun downloadPdf(@PathVariable slug: String): ResponseEntity<ByteArray> {
        val p = lookup(slug)
        recordDownloadUseCase.invoke(slug) // export = 다운로드 지표(+1). view 와 분리.
        val pdf = pdfRenderer.render(p, publicBaseUrl = pageProps.publicBaseUrl, imageEmbedUrl = imageEmbedUrl)
        val fileName = "${slugifyFilename(p.title)}.pdf"
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
            .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
            .body(pdf)
    }

    // 또한 `/api/v1/publishments/by-slug/{slug}/export.pdf` 별칭 (응답 DTO 의 pdfUrl 과 일관)
    @GetMapping("/api/v1/publishments/by-slug/{slug}/export.pdf")
    fun downloadPdfAlias(@PathVariable slug: String) = downloadPdf(slug)

    // Markdown 다운로드(export.md)는 도입 예정 — 핸들러 의도적으로 두지 않음.
    // 공개 페이지 공유 바에는 "도입 예정" 비활성 버튼만 노출(HtmlRenderer), 응답 DTO 의 markdownUrl 은 null.
    // 호출이 와도 shared-error 의 NoResourceFoundException→404 매핑으로 500(서킷 오염) 없이 404 로 떨어진다.
    // 출시 시: MarkdownRenderer 로 downloadPdf 와 대칭되는 export.md 핸들러를 복원하면 된다.

    /**
     * 안정 파일 라우트 — 첨부 원본을 클릭 시점에 presign 해 302 redirect.
     * presigned URL(만료)을 발행물에 박제하지 않아 웹/PDF/MD 어디서든 만료 없이 작동.
     *  - 가시성: `lookup(slug)` 이 발행물 접근권(PUBLIC/UNLISTED 등)을 검사(비공개면 403/404).
     *  - disposition: `inline` 요청이라도 뷰 불가/위험 타입이면 attachment 로 강등(allowlist).
     */
    @Operation(summary = "발행물 첨부 파일 (presign redirect)")
    @GetMapping("/p/{slug}/files/{markerId}")
    fun file(
        @PathVariable slug: String,
        @PathVariable markerId: String,
        @Parameter(description = "inline | attachment (기본 inline)")
        @RequestParam(required = false, defaultValue = "inline") disposition: String,
    ): ResponseEntity<Void> {
        val p = lookup(slug)
        // 스텝 첨부 + description(케이스 본문) 첨부(C1) 를 함께 조회. 본문 태그 이미지도 이 라우트로
        // 로드되므로 둘 다 뒤지지 않으면 본문 이미지가 404(엑박)가 된다.
        val att = (p.contentSnapshot.steps.asSequence().flatMap { it.attachments.asSequence() } +
            p.contentSnapshot.descriptionAttachments.asSequence())
            .firstOrNull { it.markerId == markerId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "attachment not found: $markerId")
        val effective = AttachmentRender.effectiveDisposition(disposition, att.contentType)
        val url = attachmentPresigner.signedUrl(att.storageUrl, att.contentType, effective)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "attachment has no stored object: $markerId")
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, url)
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }

    /**
     * 검색엔진 sitemap — PUBLIC+LIVE 발행물의 공개 URL(절대) 목록. 무인증 공개(게이트웨이 permitAll + 라우트).
     * 별도 인덱스 없이 live 쿼리라 visibility 를 UNLISTED 로 바꾸면 다음 조회부터 자동 제외.
     * 발행물이 sitemap URL 상한(50k)에 근접하면 sitemap index 로 분할 필요.
     */
    @Operation(summary = "sitemap.xml (공개 발행물 discovery)")
    @GetMapping("/sitemap.xml", produces = [MediaType.APPLICATION_XML_VALUE])
    fun sitemap(): ResponseEntity<String> {
        val base = pageProps.publicBaseUrl.trimEnd('/')
        val urls = listPublicUseCase.invokeAll().joinToString("\n") { p ->
            "  <url><loc>${xmlEscape("$base/p/${p.slug}")}</loc><lastmod>${p.publishedAt.toLocalDate()}</lastmod></url>"
        }
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n" +
            (if (urls.isEmpty()) "" else "$urls\n") +
            "</urlset>\n"
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/xml; charset=UTF-8")
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
            .body(xml)
    }

    /**
     * robots.txt — 크롤러에 sitemap 위치를 알리고 공개 페이지(/p/)만 크롤 허용, API 경로는 Disallow.
     */
    @Operation(summary = "robots.txt")
    @GetMapping("/robots.txt", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun robots(): ResponseEntity<String> {
        val base = pageProps.publicBaseUrl.trimEnd('/')
        val body = "User-agent: *\n" +
            "Allow: /p/\n" +
            "Disallow: /api/\n" +
            "Sitemap: $base/sitemap.xml\n"
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8")
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
            .body(body)
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun lookup(slug: String) = try {
        getBySlugUseCase.invoke(slug, viewerUserId = null)
    } catch (e: PublishmentNotFoundException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
    } catch (e: PublishmentGoneException) {
        throw ResponseStatusException(HttpStatus.GONE, e.message, e)
    } catch (e: PublishmentForbiddenException) {
        throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message, e)
    }

    /** 공개 페이지 경량 게이팅(content 역직렬화 없음). 없음→404, 내려짐→410. */
    private fun lookupMeta(slug: String) = try {
        metaUseCase.invoke(slug)
    } catch (e: PublishmentNotFoundException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
    } catch (e: PublishmentGoneException) {
        throw ResponseStatusException(HttpStatus.GONE, e.message, e)
    }

    private fun slugifyFilename(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(60).ifBlank { "publishment" }
}

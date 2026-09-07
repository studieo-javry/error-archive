package org.studieojavry.publishapi.publishment.application.usecase

import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import org.studieojavry.publishapi.publishment.domain.Visibility
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * `CasePublishment` → 단일 HTML 문서.
 *
 *  - 공개 페이지 (`/p/{slug}`) 의 응답 본문
 *  - PDF 변환의 입력 (PDF 모드 = `for-pdf=true` 시 인쇄 친화 CSS 추가)
 *
 *  Thymeleaf 같은 템플릿 엔진 X — 한 파일 안에서 끝나는 단순 string builder.
 *  XSS 방지: 사용자 텍스트는 모두 `esc()` 처리.
 */
object HtmlRenderer {

    private val DATE_FMT = DateTimeFormatter.ISO_DATE
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
    private val EXACT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")   // 발행물은 영구 자산 → 연도 포함(월/일만이면 몇 년도인지 모호)
    private val SNIPPET_MARKER = Regex("@snippet\\([A-Za-z0-9_-]+\\)")
    private val ATTACH_MARKER = Regex("@attach\\([A-Za-z0-9_-]+\\)")

    /**
     * @param publicBaseUrl PDF/절대링크용 베이스. 웹(forPdf=false)은 상대경로(안정 라우트).
     * @param imageEmbedUrl PDF 이미지 임베드용 presigned URL 해석기(objectKey→url). 웹은 안정 라우트 사용.
     */
    fun render(
        p: CasePublishment,
        forPdf: Boolean = false,
        publicBaseUrl: String = "",
        imageEmbedUrl: (String) -> String? = { null },
    ): String {
        val opts = p.options
        val isDark = false   // theme 옵션 제거 — 발행물은 단일 라이트 톤
        val lang = "ko"      // language 옵션 제거 — 고정

        val sb = StringBuilder()
        sb.appendLine("<!doctype html>")
        sb.appendLine("<html lang=\"$lang\">")
        sb.appendLine("<head>")
        sb.appendLine("<meta charset=\"UTF-8\"/>")
        sb.appendLine("<title>${esc(p.title)}</title>")
        if (!forPdf) {
            sb.appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>")
            // 검색엔진 색인 제어 — PUBLIC 만 색인 허용. UNLISTED(및 잔존 PRIVATE)는 noindex
            // → "링크 아는 사람만" 을 기술적으로 실현(크롤러가 검색 결과에서 제외). canonical 도 PUBLIC 만.
            val indexable = p.visibility == Visibility.PUBLIC
            sb.appendLine("<meta name=\"robots\" content=\"${if (indexable) "index,follow" else "noindex,nofollow"}\"/>")
            if (indexable && publicBaseUrl.isNotBlank()) {
                sb.appendLine("<link rel=\"canonical\" href=\"$publicBaseUrl/p/${p.slug}\"/>")
            }
            // 공유 링크 프리뷰(Open Graph / Twitter 카드). 서버 렌더 HTML 이라 크롤러가 JS 없이 즉시 읽는다.
            // robots/canonical 과 독립 — UNLISTED 도 링크 공유용이라 카드는 뜨게 두고(OG 유지), 검색 색인만 noindex 로 막는다.
            val desc = metaDescription(p)
            val ogImage = firstImageAbsUrl(p, publicBaseUrl)
            sb.appendLine("<meta name=\"description\" content=\"${esc(desc)}\"/>")
            sb.appendLine("<meta property=\"og:type\" content=\"article\"/>")
            sb.appendLine("<meta property=\"og:site_name\" content=\"Error Archive\"/>")
            sb.appendLine("<meta property=\"og:title\" content=\"${esc(p.title)}\"/>")
            sb.appendLine("<meta property=\"og:description\" content=\"${esc(desc)}\"/>")
            if (publicBaseUrl.isNotBlank()) {
                sb.appendLine("<meta property=\"og:url\" content=\"$publicBaseUrl/p/${p.slug}\"/>")
            }
            ogImage?.let { sb.appendLine("<meta property=\"og:image\" content=\"$it\"/>") }
            sb.appendLine("<meta name=\"twitter:card\" content=\"${if (ogImage != null) "summary_large_image" else "summary"}\"/>")
            sb.appendLine("<meta name=\"twitter:title\" content=\"${esc(p.title)}\"/>")
            sb.appendLine("<meta name=\"twitter:description\" content=\"${esc(desc)}\"/>")
            ogImage?.let { sb.appendLine("<meta name=\"twitter:image\" content=\"$it\"/>") }
        }
        sb.appendLine("<style>${styles(isDark, forPdf)}</style>")
        sb.appendLine("</head>")
        sb.appendLine("<body class=\"${if (isDark) "dark" else "light"}\">")
        sb.appendLine("<article class=\"doc\">")

        // Header
        sb.appendLine("<header class=\"head\">")
        sb.appendLine("<h1>${esc(p.title)}</h1>")
        p.summary?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine("<p class=\"summary\">${esc(it)}</p>")
        }
        sb.appendLine("<div class=\"meta\">")
        opts.authorDisplayName?.let { sb.appendLine("<span class=\"by\">by <b>${esc(it)}</b></span>") }
        sb.appendLine("<span class=\"date\">${p.publishedAt.toLocalDate().format(DATE_FMT)}</span>")
        sb.appendLine("</div>")
        if (p.contentSnapshot.tags.isNotEmpty()) {
            sb.appendLine("<div class=\"tags\">")
            p.contentSnapshot.tags.forEach { sb.appendLine("<span class=\"tag\">${esc(it)}</span>") }
            sb.appendLine("</div>")
        }
        sb.appendLine("</header>")

        // Error
        p.contentSnapshot.snapshot?.let { es ->
            sb.appendLine("<section class=\"section\">")
            sb.appendLine("<h2>Error</h2>")
            es.exceptionClass?.let { sb.appendLine("<code class=\"exc\">${esc(it)}</code>") }
            es.exceptionMessage?.let { sb.appendLine("<blockquote>${esc(it)}</blockquote>") }
            if (!es.rawStackTrace.isNullOrBlank()) {
                val trimmed = es.rawStackTrace.lineSequence().take(20).joinToString("\n")
                sb.appendLine("<pre class=\"stack\"><code>${esc(trimmed)}</code></pre>")
            }
            sb.appendLine("</section>")
        }

        // Description (Context) — 마커는 텍스트에서 제거, 참조 자산은 아래 블록으로(스텝과 동일 패턴, C1).
        val descText = p.contentSnapshot.description?.let { stripMarkers(it) }?.takeIf { it.isNotBlank() }
        val descSnippets = p.contentSnapshot.descriptionSnippets
        val descAttachments = p.contentSnapshot.descriptionAttachments
        if (descText != null || descSnippets.isNotEmpty() || descAttachments.isNotEmpty()) {
            sb.appendLine("<section class=\"section\">")
            sb.appendLine("<h2>Context</h2>")
            if (descText != null) sb.appendLine("<p>${escMultiline(descText)}</p>")
            descSnippets.forEach { renderSnippet(sb, it, opts, forPdf) }
            descAttachments.forEach { att -> renderAttachment(sb, att, p.slug, forPdf, publicBaseUrl, imageEmbedUrl) }
            sb.appendLine("</section>")
        }

        // Journey
        if (p.contentSnapshot.steps.isNotEmpty()) {
            sb.appendLine("<section class=\"section\">")
            sb.appendLine("<h2>The Journey</h2>")
            p.contentSnapshot.steps.forEachIndexed { i, st ->
                renderStep(sb, i + 1, st, opts, p.slug, p.contentSnapshot.originalCaseCreatedAt, forPdf, publicBaseUrl, imageEmbedUrl)
            }
            sb.appendLine("</section>")
        }

        // Solutions
        if (p.contentSnapshot.solutions.isNotEmpty()) {
            sb.appendLine("<section class=\"section solution\">")
            sb.appendLine("<h2>Solution</h2>")
            p.contentSnapshot.solutions.forEach { sol ->
                sol.title?.let { sb.appendLine("<h3>${esc(it)}</h3>") }
                if (sol.body.isNotBlank()) sb.appendLine("<p>${escMultiline(sol.body)}</p>")
            }
            sb.appendLine("</section>")
        }

        sb.appendLine("<footer class=\"foot\">")
        sb.appendLine("<span>Published with <b>Error Archive</b></span>")
        sb.appendLine("<span class=\"slug\">${esc(p.slug)}</span>")
        sb.appendLine("</footer>")

        sb.appendLine("</article>")
        sb.appendLine("</body></html>")
        return sb.toString()
    }

    private fun renderStep(
        sb: StringBuilder,
        num: Int,
        st: ContentSnapshot.StepDoc,
        opts: PublishOptions,
        slug: String,
        caseCreatedAt: LocalDateTime,
        forPdf: Boolean,
        publicBaseUrl: String,
        imageEmbedUrl: (String) -> String?,
    ) {
        val outcomeClass = when (st.outcome) {
            "RESOLVED" -> "ok"
            "FAILED"   -> "fail"
            "IN_PROGRESS" -> "wip"
            else -> "neutral"
        }
        val durTxt = if (opts.showStepDuration && st.durationMinutes != null && st.durationMinutes > 0)
            " <span class=\"dur\">· ${DurationFormat.humanize(st.durationMinutes)}</span>" else ""
        val timeTxt = stepTimeLabel(opts.timeStyle, st.occurredAt, caseCreatedAt)
            ?.let { " <span class=\"step-time\">$it</span>" } ?: ""
        val title = st.title?.takeIf { it.isNotBlank() } ?: "Step $num"
        sb.appendLine("<div class=\"step $outcomeClass\">")
        sb.appendLine("<h3>Step $num — ${esc(title)}$timeTxt$durTxt</h3>")
        if (st.body.isNotBlank()) {
            sb.appendLine("<p>${escMultiline(stripMarkers(st.body))}</p>")
        }
        st.snippets.forEach { renderSnippet(sb, it, opts, forPdf) }
        st.attachments.forEach { att -> renderAttachment(sb, att, slug, forPdf, publicBaseUrl, imageEmbedUrl) }
        sb.appendLine("</div>")
    }

    /** 스니펫 코드블록 — 스텝/description 공용. 줄 번호(codeLineNumbers·웹 전용)는 [renderCodeLines] + `.numbered`. */
    private fun renderSnippet(sb: StringBuilder, sn: ContentSnapshot.SnippetDoc, opts: PublishOptions, forPdf: Boolean) {
        sn.title?.takeIf { it.isNotBlank() }?.let { sb.appendLine("<div class=\"snip-title\">${esc(it)}</div>") }
        val numCls = if (opts.codeLineNumbers && !forPdf) " numbered" else ""
        sb.appendLine("<pre class=\"code$numCls\" data-lang=\"${esc(sn.language.lowercase())}\"><code>${renderCodeLines(sn.code)}</code></pre>")
        sn.caption?.takeIf { it.isNotBlank() }?.let { sb.appendLine("<div class=\"cap\">${esc(it)}</div>") }
    }

    /**
     * 방향 A 2-way 렌더 — IMAGE 는 인라인, 그 외는 파일명 링크(안정 라우트).
     *  - 웹: 이미지 src = 안정 라우트(inline), 비이미지 = 상대 링크(브라우저가 inline/다운로드).
     *  - PDF: 이미지 = presigned bytes 임베드, 비이미지 = 절대 안정 링크(무만료).
     */
    private fun renderAttachment(
        sb: StringBuilder,
        att: ContentSnapshot.AttachmentRef,
        slug: String,
        forPdf: Boolean,
        publicBaseUrl: String,
        imageEmbedUrl: (String) -> String?,
    ) {
        // slug 가 있으면 발행됨 → 안정 라우트. 없으면 preview(transient) → presigned 직접(임시).
        val hasSlug = slug.isNotBlank()
        if (att.kind == "IMAGE") {
            val src = when {
                forPdf  -> att.storageUrl?.let(imageEmbedUrl)                          // PDF: presigned bytes 임베드
                hasSlug -> AttachmentRender.fileHref("", slug, att.markerId, "inline") // 발행 웹: 안정 라우트
                else    -> att.storageUrl?.let(imageEmbedUrl)                          // preview: presigned
            }
            if (src != null) sb.appendLine("<img class=\"att img\" src=\"${esc(src)}\" alt=\"${esc(att.fileName)}\"/>")
            else sb.appendLine("<div class=\"att file\">📎 ${esc(att.fileName)}</div>")
            return
        }
        val disp = AttachmentRender.effectiveDisposition("inline", att.contentType)
        val href = if (hasSlug) AttachmentRender.fileHref(if (forPdf) publicBaseUrl else "", slug, att.markerId, disp)
                   else att.storageUrl?.let(imageEmbedUrl)   // preview: presigned inline
        if (href != null) {
            val label = if (disp == "inline") "새 탭에서 보기" else "다운로드"
            val target = if (forPdf) "" else " target=\"_blank\" rel=\"noopener\""
            sb.appendLine("<div class=\"att file\">📎 <a href=\"${esc(href)}\"$target>${esc(att.fileName)}</a> <span class=\"att-act\">$label</span></div>")
        } else {
            sb.appendLine("<div class=\"att file\">📎 ${esc(att.fileName)}</div>")
        }
    }

    private fun stepTimeLabel(style: PublishOptions.TimeStyle, occurredAt: LocalDateTime?, caseCreatedAt: LocalDateTime): String? {
        if (occurredAt == null) return null
        return when (style) {
            PublishOptions.TimeStyle.EXACT -> EXACT_FMT.format(occurredAt)
            PublishOptions.TimeStyle.RELATIVE -> {
                val day = ChronoUnit.DAYS.between(caseCreatedAt.toLocalDate(), occurredAt.toLocalDate()) + 1
                "Day $day · ${TIME_FMT.format(occurredAt)}"
            }
            PublishOptions.TimeStyle.NONE -> null
        }
    }

    private fun stripMarkers(text: String): String =
        text.replace(SNIPPET_MARKER, "").replace(ATTACH_MARKER, "")
            .replace(Regex("[ \t]+"), " ").trim()

    /**
     * 코드를 줄 단위 블록 span(`.cl`)으로. 줄 번호 CSS(`.numbered .cl::before`)의 counter-increment 대상.
     *  - span 사이에 개행을 넣지 않는다(블록 사이 개행 텍스트노드가 pre 에서 빈 줄로 렌더되는 것 방지).
     *  - 마지막 개행 1개만 제거(끝의 유령 빈 줄 방지). 중간 빈 줄은 빈 span 으로 보존(번호도 매겨짐).
     */
    private fun renderCodeLines(code: String): String =
        code.removeSuffix("\n").split("\n")
            .joinToString("") { "<span class=\"cl\">${esc(it)}</span>" }

    /** 공유 카드 설명 — summary 우선, 없으면 본문(description)에서 마커 제거 + 공백 정리 후 ~200자 절단. */
    private fun metaDescription(p: CasePublishment): String {
        val raw = p.summary?.takeIf { it.isNotBlank() }
            ?: p.contentSnapshot.description?.let { stripMarkers(it) }
            ?: ""
        val oneLine = raw.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length > 200) oneLine.take(197).trimEnd() + "…" else oneLine
    }

    /**
     * og:image 용 첫 IMAGE 첨부의 **절대** 안정 URL. 이미지 없거나 baseUrl 없으면 null.
     * `/p/{slug}/files/{markerId}` 는 매 요청마다 presigned 로 302 → 만료 없이 크롤러가 원본 이미지에 도달.
     */
    private fun firstImageAbsUrl(p: CasePublishment, publicBaseUrl: String): String? {
        if (publicBaseUrl.isBlank()) return null
        val marker = p.contentSnapshot.steps.asSequence()
            .flatMap { it.attachments.asSequence() }
            .firstOrNull { it.kind == "IMAGE" }
            ?.markerId ?: return null
        return "${publicBaseUrl.trimEnd('/')}/p/${p.slug}/files/$marker"
    }

    private fun esc(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun escMultiline(s: String): String =
        esc(s).replace("\n", "<br/>")

    /* ── styles ───────────────────────────────────────────────────────────── */
    private fun styles(dark: Boolean, forPdf: Boolean): String = """
        * { box-sizing: border-box; }
        body { margin: 0; padding: 0; font-family: 'Noto Sans KR', 'Helvetica Neue', Arial, sans-serif; line-height: 1.6;
               background: ${if (dark) "#0f1115" else "#ffffff"}; color: ${if (dark) "#e6e8ee" else "#1a1f24"}; }
        .doc { max-width: 740px; margin: 0 auto; padding: ${if (forPdf) "20px 24px" else "48px 24px 96px"}; }
        h1, h2, h3 { font-family: 'Merriweather', Georgia, serif; }
        h1 { font-size: 28px; line-height: 1.25; margin: 0 0 12px; letter-spacing: -.4px; }
        h2 { font-size: 20px; margin: 32px 0 12px; }
        h3 { font-size: 16px; margin: 18px 0 8px; }
        p { margin: 8px 0 12px; }
        .summary { color: ${if (dark) "#aab0bc" else "#52525b"}; font-size: 15px; margin: 0 0 16px; }
        .meta { font-size: 12px; color: ${if (dark) "#8b929c" else "#71717a"}; margin-bottom: 8px; }
        .meta .date { margin-left: 8px; }
        .tags { margin: 12px 0 0; display: flex; gap: 6px; flex-wrap: wrap; }
        .tag { font-size: 11px; padding: 2px 8px; border-radius: 4px;
               background: ${if (dark) "#1f2229" else "#f4f4f5"};
               color: ${if (dark) "#9aa0ab" else "#71717a"};
               font-family: ui-monospace, monospace; }
        .tag::before { content: "#"; opacity: .5; margin-right: 2px; }
        .head { border-bottom: 1px solid ${if (dark) "#262a32" else "#e4e4e7"}; padding-bottom: 20px; margin-bottom: 24px; }
        .section { margin: 28px 0; }
        .exc { display: inline-block; background: ${if (dark) "#2a1e1e" else "#fef2f2"};
               color: ${if (dark) "#fda4af" else "#b91c1c"}; padding: 4px 10px; border-radius: 4px;
               font-family: ui-monospace, monospace; font-size: 13px; }
        blockquote { border-left: 3px solid ${if (dark) "#3b3f47" else "#d4d4d8"}; margin: 8px 0; padding: 6px 12px;
                     color: ${if (dark) "#aab0bc" else "#52525b"}; }
        pre.stack, pre.code { background: ${if (dark) "#0a0c10" else "#f6f8fa"}; border: 1px solid ${if (dark) "#1f2229" else "#e4e4e7"};
               padding: 12px 14px; border-radius: 8px; overflow-x: auto;
               font: 12px/1.5 'JetBrains Mono', ui-monospace, monospace;
               color: ${if (dark) "#e6e8ee" else "#1a1f24"}; }
        pre.stack { color: ${if (dark) "#fbbf24" else "#92400e"}; }
        pre.code .cl { display: block; white-space: pre; }
        pre.code.numbered code { counter-reset: ln; }
        pre.code.numbered .cl { counter-increment: ln; position: relative; padding-left: 3.4em; }
        pre.code.numbered .cl::before { content: counter(ln); position: absolute; left: 0; top: 0;
               width: 2.6em; text-align: right; padding-right: .7em;
               color: ${if (dark) "#5a616b" else "#b0b4bb"};
               border-right: 1px solid ${if (dark) "#1f2229" else "#e4e4e7"};
               -webkit-user-select: none; user-select: none; }
        .step { border-left: 3px solid ${if (dark) "#3b3f47" else "#e4e4e7"}; padding-left: 16px; margin: 18px 0; }
        .step.ok   { border-color: ${if (dark) "#34d399" else "#10b981"}; }
        .step.fail { border-color: ${if (dark) "#fb7185" else "#ef4444"}; }
        .step.wip  { border-color: ${if (dark) "#fbbf24" else "#f59e0b"}; }
        .step .dur { font-size: 11px; color: ${if (dark) "#8b929c" else "#71717a"};
                     font-family: ui-monospace, monospace; }
        .step .step-time { font-size: 11px; color: ${if (dark) "#8b929c" else "#71717a"};
                     font-family: ui-monospace, monospace; }
        .att .att-act { font-size: 10px; color: ${if (dark) "#8b929c" else "#a1a1aa"};
                     font-family: ui-monospace, monospace; margin-left: 4px; }
        .snip-title { font-weight: 600; font-size: 12px; margin: 6px 0 2px; color: ${if (dark) "#aab0bc" else "#52525b"}; }
        .cap { font-size: 11px; color: ${if (dark) "#8b929c" else "#71717a"}; margin: -8px 0 12px; font-style: italic; }
        .att { font-size: 12px; color: ${if (dark) "#aab0bc" else "#52525b"}; margin: 6px 0; }
        .att a { color: ${if (dark) "#a5b4fc" else "#4f46e5"}; }
        .att.img { max-width: 100%; border-radius: 6px; margin: 8px 0; }
        .section.solution { background: ${if (dark) "#0a1410" else "#f0fdf4"}; padding: 18px 20px;
                            border-radius: 10px; border: 1px solid ${if (dark) "#10402d" else "#bbf7d0"}; }
        .foot { border-top: 1px solid ${if (dark) "#262a32" else "#e4e4e7"}; margin-top: 48px; padding-top: 14px;
                display: flex; justify-content: space-between; font-size: 11px;
                color: ${if (dark) "#8b929c" else "#71717a"}; font-family: ui-monospace, monospace; }
        ${if (forPdf) "@page { size: A4; margin: 16mm; }" else ""}
    """.trimIndent()
}

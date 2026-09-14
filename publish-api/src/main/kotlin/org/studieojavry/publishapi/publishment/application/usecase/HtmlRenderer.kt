package org.studieojavry.publishapi.publishment.application.usecase

import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import org.studieojavry.publishapi.publishment.domain.Visibility
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * `CasePublishment` → 단일 HTML 문서 (테크 블로그 아티클).
 *
 *  - 공개 페이지 (`/p/{slug}`) 의 응답 본문
 *  - PDF 변환의 입력 (PDF 모드 = `forPdf=true` 시 인쇄 친화 CSS + 웹폰트 미로드)
 *
 *  디자인(단일 라이트 톤): 배경 #f7f7f5 · 본문 Manrope · 제목 Fraunces · 코드/에러 다크 ·
 *  요약 TL;DR(밑줄 헤딩) · 에러 터미널 창 · 인용 GitHub md 회색 · 해결 넘버드+하이라이터.
 *  웹폰트는 Google Fonts + Pretendard(CDN)로 로드, PDF 는 클래스패스 Noto Sans KR 로 폴백.
 *
 *  Thymeleaf 같은 템플릿 엔진 X — 한 파일 string builder. XSS 방지: 사용자 텍스트는 모두 `esc()`.
 */
object HtmlRenderer {

    private val DATE_FMT = DateTimeFormatter.ISO_DATE
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
    private val EXACT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
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
        val lang = "ko"
        val snap = p.contentSnapshot

        val sb = StringBuilder()
        sb.appendLine("<!doctype html>")
        sb.appendLine("<html lang=\"$lang\">")
        sb.appendLine("<head>")
        sb.appendLine("<meta charset=\"UTF-8\"/>")
        sb.appendLine("<title>${esc(p.title)}</title>")
        if (!forPdf) {
            sb.appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>")
            // 웹폰트 (PDF 는 미로드 → 폰트 스택의 Noto Sans KR 로 폴백)
            sb.appendLine("<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\"/>")
            sb.appendLine("<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin/>")
            sb.appendLine("<link href=\"https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;600;700;800&family=Fraunces:opsz,wght@9..144,500;9..144,600&family=JetBrains+Mono:wght@400;500;600&family=Nanum+Myeongjo:wght@400;700&display=swap\" rel=\"stylesheet\"/>")
            sb.appendLine("<link href=\"https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable.min.css\" rel=\"stylesheet\"/>")
            // 검색엔진 색인 제어 — PUBLIC 만 색인 허용.
            val indexable = p.visibility == Visibility.PUBLIC
            sb.appendLine("<meta name=\"robots\" content=\"${if (indexable) "index,follow" else "noindex,nofollow"}\"/>")
            if (indexable && publicBaseUrl.isNotBlank()) {
                sb.appendLine("<link rel=\"canonical\" href=\"$publicBaseUrl/p/${p.slug}\"/>")
            }
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
        sb.appendLine("<style>${styles(forPdf)}</style>")
        sb.appendLine("</head>")
        sb.appendLine("<body>")
        sb.appendLine("<article class=\"doc\">")

        // ── 헤더 ──
        sb.appendLine("<header class=\"head\">")
        snap.tags.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine("<div class=\"kicker\"><span class=\"dot\"></span>${esc(it)}</div>")
        }
        sb.appendLine("<h1>${esc(p.title)}</h1>")
        // 요약 = TL;DR (밑줄 헤딩)
        p.summary?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine("<div class=\"tldr\"><div class=\"lb\">TL;DR</div><p class=\"t\">${esc(it)}</p></div>")
        }
        // 저자 바
        val author = opts.authorDisplayName?.takeIf { it.isNotBlank() }
        val initial = author?.trim()?.firstOrNull()?.toString() ?: "·"
        sb.appendLine("<div class=\"byline\">")
        sb.appendLine("<span class=\"avatar\">${esc(initial)}</span>")
        sb.appendLine("<div class=\"who\">")
        sb.appendLine("<div class=\"n\">${esc(author ?: "익명")}</div>")
        sb.appendLine("<div class=\"meta\">${p.publishedAt.toLocalDate().format(DATE_FMT)}</div>")
        sb.appendLine("</div>")
        sb.appendLine("</div>")
        if (snap.tags.isNotEmpty()) {
            sb.appendLine("<div class=\"tags\">")
            snap.tags.forEach { sb.appendLine("<span class=\"tag\">${esc(it)}</span>") }
            sb.appendLine("</div>")
        }
        sb.appendLine("</header>")

        // ── Error (터미널 창) ──
        snap.snapshot?.let { es ->
            val excLine = when {
                es.exceptionClass != null && es.exceptionMessage != null ->
                    "<span class=\"exc\">${esc(es.exceptionClass)}</span>: ${esc(es.exceptionMessage)}"
                es.exceptionClass != null -> "<span class=\"exc\">${esc(es.exceptionClass)}</span>"
                es.exceptionMessage != null -> esc(es.exceptionMessage)
                else -> null
            }
            val stack = es.rawStackTrace?.takeIf { it.isNotBlank() }
                ?.lineSequence()?.take(20)?.joinToString("\n")
            if (excLine != null || stack != null) {
                sb.appendLine("<section class=\"section\">")
                sb.appendLine("<div class=\"sec-eyebrow\">The Error</div>")
                sb.appendLine("<h2 class=\"sec\">무엇이 터졌나</h2>")
                sb.appendLine("<div class=\"crash\">")
                sb.appendLine("<div class=\"tbar\"><span class=\"dots\"><i></i><i></i><i></i></span><span class=\"fname\">stacktrace.log</span></div>")
                val tb = StringBuilder()
                excLine?.let { tb.append(it) }
                if (stack != null) {
                    if (tb.isNotEmpty()) tb.append("\n")
                    tb.append("<span class=\"dim\">${esc(stack)}</span>")
                }
                sb.appendLine("<pre class=\"tbody\">$tb</pre>")
                sb.appendLine("</div>")
                sb.appendLine("</section>")
            }
        }

        // ── Context ──
        val descText = snap.description?.let { stripMarkers(it) }?.takeIf { it.isNotBlank() }
        if (descText != null || snap.descriptionSnippets.isNotEmpty() || snap.descriptionAttachments.isNotEmpty()) {
            sb.appendLine("<section class=\"section\">")
            sb.appendLine("<h2 class=\"sec\">Context</h2>")
            if (descText != null) renderProse(sb, descText)
            snap.descriptionSnippets.forEach { renderSnippet(sb, it, opts, forPdf) }
            snap.descriptionAttachments.forEach { att -> renderAttachment(sb, att, p.slug, forPdf, publicBaseUrl, imageEmbedUrl) }
            sb.appendLine("</section>")
        }

        // ── The Journey ──
        if (snap.steps.isNotEmpty()) {
            sb.appendLine("<section class=\"section\">")
            sb.appendLine("<div class=\"sec-eyebrow\">The Journey</div>")
            sb.appendLine("<h2 class=\"sec\">추적 과정</h2>")
            snap.steps.forEachIndexed { i, st ->
                renderStep(sb, i + 1, st, opts, p.slug, snap.originalCaseCreatedAt, forPdf, publicBaseUrl, imageEmbedUrl)
            }
            sb.appendLine("</section>")
        }

        // ── Solution (넘버드 + 하이라이터 합본) ──
        if (snap.solutions.isNotEmpty()) {
            sb.appendLine("<section class=\"section\">")
            sb.appendLine("<div class=\"sec-eyebrow\">The Fix</div>")
            sb.appendLine("<h2 class=\"sec\">해결</h2>")
            snap.solutions.forEachIndexed { i, sol ->
                val name = sol.title?.takeIf { it.isNotBlank() } ?: "해결 ${i + 1}"
                sb.appendLine("<div class=\"solx\">")
                sb.appendLine("<div class=\"num\">${(i + 1).toString().padStart(2, '0')}</div>")
                sb.appendLine("<div class=\"rt\">")
                sb.appendLine("<div class=\"sol-name\"><span class=\"hl\">${esc(name)}</span></div>")
                if (sol.body.isNotBlank()) sb.appendLine("<div class=\"sol-body\">${inlineMd(sol.body)}</div>")
                sb.appendLine("</div>")
                sb.appendLine("</div>")
            }
            sb.appendLine("</section>")
        }

        // ── 푸터 ──
        sb.appendLine("<footer class=\"foot\">")
        sb.appendLine("<span>Published with <b>Error Archive</b></span>")
        sb.appendLine("<span class=\"slug\">/p/${esc(p.slug)}</span>")
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
            "FAILED" -> "fail"
            "IN_PROGRESS" -> "wip"
            else -> "neutral"
        }
        val chipLabel = when (st.outcome) {
            "RESOLVED" -> "RESOLVED"
            "FAILED" -> "FAILED"
            "IN_PROGRESS" -> "IN PROGRESS"
            else -> null
        }
        val chip = chipLabel?.let { " <span class=\"chip\">$it</span>" } ?: ""
        val durTxt = if (opts.showStepDuration && st.durationMinutes != null && st.durationMinutes > 0)
            " <span class=\"time\">· ${DurationFormat.humanize(st.durationMinutes)}</span>" else ""
        val timeTxt = stepTimeLabel(opts.timeStyle, st.occurredAt, caseCreatedAt)
            ?.let { " <span class=\"time\">$it</span>" } ?: ""
        val title = st.title?.takeIf { it.isNotBlank() } ?: "Step $num"
        sb.appendLine("<div class=\"step $outcomeClass\">")
        sb.appendLine("<h3>Step $num — ${esc(title)}$chip$timeTxt$durTxt</h3>")
        if (st.body.isNotBlank()) {
            renderProse(sb, stripMarkers(st.body))
        }
        st.snippets.forEach { renderSnippet(sb, it, opts, forPdf) }
        st.attachments.forEach { att -> renderAttachment(sb, att, slug, forPdf, publicBaseUrl, imageEmbedUrl) }
        sb.appendLine("</div>")
    }

    /** 스니펫 코드블록(다크) — 상단 랭귀지 탭 + 신호등 점. 줄 번호(codeLineNumbers·웹 전용)는 `.numbered`. */
    private fun renderSnippet(sb: StringBuilder, sn: ContentSnapshot.SnippetDoc, opts: PublishOptions, forPdf: Boolean) {
        sn.title?.takeIf { it.isNotBlank() }?.let { sb.appendLine("<div class=\"snip-title\">${esc(it)}</div>") }
        val numCls = if (opts.codeLineNumbers && !forPdf) " numbered" else ""
        sb.appendLine("<div class=\"codewrap\">")
        sb.appendLine("<div class=\"code-bar\"><span class=\"lang\">${esc(sn.language.lowercase())}</span><span class=\"dots\"><i></i><i></i><i></i></span></div>")
        sb.appendLine("<pre class=\"code$numCls\"><code>${renderCodeLines(sn.code)}</code></pre>")
        sb.appendLine("</div>")
        sn.caption?.takeIf { it.isNotBlank() }?.let { sb.appendLine("<div class=\"cap\">${esc(it)}</div>") }
    }

    /**
     * 방향 A 2-way 렌더 — IMAGE 는 인라인, 그 외는 파일명 링크(안정 라우트).
     */
    private fun renderAttachment(
        sb: StringBuilder,
        att: ContentSnapshot.AttachmentRef,
        slug: String,
        forPdf: Boolean,
        publicBaseUrl: String,
        imageEmbedUrl: (String) -> String?,
    ) {
        val hasSlug = slug.isNotBlank()
        if (att.kind == "IMAGE") {
            val src = when {
                forPdf -> att.storageUrl?.let(imageEmbedUrl)
                hasSlug -> AttachmentRender.fileHref("", slug, att.markerId, "inline")
                else -> att.storageUrl?.let(imageEmbedUrl)
            }
            if (src != null) sb.appendLine("<img class=\"att img\" src=\"${esc(src)}\" alt=\"${esc(att.fileName)}\"/>")
            else sb.appendLine("<div class=\"att file\">📎 ${esc(att.fileName)}</div>")
            return
        }
        val disp = AttachmentRender.effectiveDisposition("inline", att.contentType)
        val href = if (hasSlug) AttachmentRender.fileHref(if (forPdf) publicBaseUrl else "", slug, att.markerId, disp)
        else att.storageUrl?.let(imageEmbedUrl)
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

    /** 코드를 줄 단위 span(`.cl`)으로. 줄 번호 CSS(`.numbered .cl::before`)의 counter-increment 대상. */
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

    /** og:image 용 첫 IMAGE 첨부의 **절대** 안정 URL. 이미지 없거나 baseUrl 없으면 null. */
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

    /** 인라인: esc → `code` 를 <code> 로 → 개행 <br/>. 블록 요소 없음(<p>/<div> 내부 전용). */
    private fun inlineMd(s: String): String =
        esc(s)
            .replace(Regex("`([^`\\n]+)`")) { "<code>${it.groupValues[1]}</code>" }
            .replace("\n", "<br/>")

    /**
     * 블록 프로즈 렌더. 빈 줄로 문단 분리, `>` 로 시작하는 줄(들)은 blockquote 로 묶는다.
     * 각 조각 텍스트는 [inlineMd] 로(인라인 코드 + 줄바꿈) 처리. XSS 는 esc 로 차단.
     */
    private fun renderProse(sb: StringBuilder, raw: String) {
        val lines = raw.split("\n")
        val para = StringBuilder()
        fun flushPara() {
            if (para.isNotBlank()) sb.appendLine("<p>${inlineMd(para.toString().trim('\n'))}</p>")
            para.setLength(0)
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith(">") -> {
                    flushPara()
                    val q = StringBuilder()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        val content = lines[i].trimStart().removePrefix(">").removePrefix(" ")
                        if (q.isNotEmpty()) q.append("\n")
                        q.append(content)
                        i++
                    }
                    sb.appendLine("<blockquote>${inlineMd(q.toString())}</blockquote>")
                }
                line.isBlank() -> { flushPara(); i++ }
                else -> { if (para.isNotEmpty()) para.append("\n"); para.append(line); i++ }
            }
        }
        flushPara()
    }

    /* ── styles (단일 라이트 · FE 라이트 배경 #f7f7f5) ─────────────────────────── */
    private fun styles(forPdf: Boolean): String = """
        :root{
          --paper:#f7f7f5; --ink:#1a1815; --sub:#5c5852; --faint:#a8a29a; --hair:#e6e4e0; --hair-2:#efedea;
          --accent:#047857; --accent-ink:#065f46; --accent-soft:#ecfdf5; --accent-line:#b6e6cf;
          --rose:#be123c; --code-bg:#0e1116; --code-ink:#e7edf3; --code-line:#20262e;
          --sans:'Manrope','Pretendard Variable',Pretendard,'Noto Sans KR',-apple-system,BlinkMacSystemFont,system-ui,sans-serif;
          --serif:'Fraunces','Nanum Myeongjo','Noto Sans KR',Georgia,serif;
          --mono:'JetBrains Mono','Pretendard Variable','Noto Sans KR',ui-monospace,Menlo,monospace;
        }
        *{box-sizing:border-box;margin:0}
        body{background:var(--paper);color:var(--ink);font-family:var(--sans);line-height:1.6;
             -webkit-font-smoothing:antialiased;-moz-osx-font-smoothing:grayscale}
        .doc{max-width:720px;margin:0 auto;padding:${if (forPdf) "8px 0 20px" else "56px 24px 96px"}}

        /* 헤더 */
        .head{padding-bottom:26px;margin-bottom:34px;border-bottom:1px solid var(--hair)}
        .kicker{display:inline-flex;align-items:center;gap:7px;font:600 11px var(--mono);letter-spacing:.08em;
               text-transform:uppercase;color:var(--accent-ink);margin-bottom:16px}
        .kicker .dot{width:6px;height:6px;border-radius:50%;background:var(--accent)}
        h1{font-family:var(--serif);font-weight:600;font-size:36px;line-height:1.15;letter-spacing:-.02em;margin:0 0 20px}
        .tldr{margin-top:4px}
        .tldr .lb{font-family:var(--sans);font-weight:800;font-size:13px;letter-spacing:-.01em;color:var(--ink);
                 position:relative;display:inline-block;margin-bottom:10px;padding-bottom:3px}
        .tldr .lb::after{content:"";position:absolute;left:0;right:0;bottom:0;height:2px;background:var(--accent)}
        .tldr .t{font-size:16px;line-height:1.62;color:var(--sub);margin:0}
        .byline{display:flex;align-items:center;gap:12px;margin-top:26px}
        .avatar{width:42px;height:42px;border-radius:50%;background:linear-gradient(135deg,var(--accent),#0ea5e9);
               color:#fff;display:grid;place-items:center;font:600 16px var(--sans);flex-shrink:0}
        .who .n{font:600 14px var(--sans);color:var(--ink)}
        .who .meta{font:400 13px var(--mono);color:var(--faint);margin-top:2px}
        .tags{display:flex;flex-wrap:wrap;gap:8px;margin-top:20px}
        .tag{font:500 12px var(--sans);color:var(--sub);background:#fff;border:1px solid var(--hair);padding:5px 12px;border-radius:999px}
        .tag::before{content:"#";color:var(--faint);margin-right:1px}

        /* 섹션 공통 */
        .section{margin:44px 0}
        .sec-eyebrow{font:600 11px var(--mono);letter-spacing:.1em;text-transform:uppercase;color:var(--accent)}
        .sec-eyebrow + h2.sec{margin-top:6px}
        h2.sec{font-family:var(--serif);font-weight:600;font-size:22px;line-height:1.3;letter-spacing:-.01em;color:var(--ink);margin:0 0 14px}
        p{margin:0 0 20px;font-size:16px;line-height:1.75;color:#292524}
        strong,b{font-weight:600;color:var(--ink)}
        a{color:var(--accent-ink)}
        code{font-family:var(--mono);font-size:.84em;background:var(--hair-2);border:1px solid var(--hair);padding:1px 5px;border-radius:5px;color:#9a3412}

        /* Error 터미널 창 */
        .crash{margin:8px 0 24px;border:1px solid var(--code-line);border-radius:12px;overflow:hidden;background:var(--code-bg)}
        .crash .tbar{display:flex;align-items:center;gap:10px;padding:9px 14px;border-bottom:1px solid var(--code-line);background:rgba(255,255,255,.03)}
        .crash .dots{display:flex;gap:6px}
        .crash .dots i{width:11px;height:11px;border-radius:50%;display:block}
        .crash .dots i:nth-child(1){background:#ff5f56}
        .crash .dots i:nth-child(2){background:#ffbd2e}
        .crash .dots i:nth-child(3){background:#27c93f}
        .crash .fname{font:500 12px var(--mono);color:#8b949e}
        .crash .tbody{margin:0;padding:15px 16px;font:13px/1.7 var(--mono);color:#e7edf3;overflow-x:auto;white-space:pre-wrap;word-break:break-word}
        .crash .tbody .exc{color:#ff7b72;font-weight:600}
        .crash .tbody .dim{color:#6b7280}

        /* 코드 블록(다크) */
        .snip-title{font-weight:600;font-size:13px;margin:6px 0 4px;color:var(--sub)}
        .codewrap{margin:16px 0 24px;border:1px solid var(--code-line);border-radius:12px;overflow:hidden;background:var(--code-bg)}
        .code-bar{display:flex;align-items:center;justify-content:space-between;padding:9px 14px;background:rgba(255,255,255,.03);border-bottom:1px solid var(--code-line)}
        .code-bar .lang{font:600 11px var(--mono);letter-spacing:.06em;text-transform:uppercase;color:#8b949e}
        .code-bar .dots{display:flex;gap:6px}
        .code-bar .dots i{width:10px;height:10px;border-radius:50%;background:#30363d;display:block}
        pre.code{margin:0;padding:15px 16px;overflow-x:auto;color:var(--code-ink);font:13px/1.75 var(--mono)}
        pre.code .cl{display:block;white-space:pre}
        pre.code.numbered code{counter-reset:ln}
        pre.code.numbered .cl{counter-increment:ln;position:relative;padding-left:3.2em}
        pre.code.numbered .cl::before{content:counter(ln);position:absolute;left:0;top:0;width:2.4em;text-align:right;
               padding-right:.8em;color:#4b5563;border-right:1px solid var(--code-line);-webkit-user-select:none;user-select:none}
        .cap{font-size:13px;color:var(--faint);margin:8px 2px 0;font-style:italic}

        /* 인용 — GitHub md 스타일(본문 폰트 + 회색 톤) */
        blockquote{margin:26px 0;padding:2px 0 2px 18px;border-left:3px solid #dcd8d1;
                  font-family:var(--sans);font-weight:400;font-size:16px;line-height:1.65;color:#6f6a63}

        /* The Journey — 스텝 */
        .step{border-left:2px solid var(--hair);padding-left:18px;margin:20px 0}
        .step.ok{border-color:var(--accent)}
        .step.fail{border-color:var(--rose)}
        .step.wip{border-color:#b45309}
        .step h3{font-family:var(--sans);font-weight:600;font-size:16px;color:var(--ink);margin:0 0 5px;display:flex;align-items:baseline;gap:9px;flex-wrap:wrap}
        .step .chip{font:600 10px var(--mono);letter-spacing:.03em;padding:2px 8px;border-radius:5px}
        .step.ok .chip{background:var(--accent-soft);color:var(--accent-ink)}
        .step.fail .chip{background:#fff1f2;color:#be123c}
        .step.wip .chip{background:#fffbeb;color:#b45309}
        .step .time{font:400 12px var(--mono);color:var(--faint)}
        .step p{margin:0 0 12px}

        /* 첨부 */
        .att{font-size:13px;color:var(--sub);margin:10px 0;font-family:var(--mono)}
        .att a{color:var(--accent-ink)}
        .att .att-act{font-size:11px;color:var(--faint);margin-left:4px}
        .att.img{max-width:100%;border-radius:10px;border:1px solid var(--hair);display:block;margin:12px 0}

        /* Solution — 넘버드 + 하이라이터 */
        .solx{display:grid;grid-template-columns:auto 1fr;gap:22px;align-items:start;margin:22px 0 4px}
        .solx .num{font-family:var(--serif);font-weight:600;font-size:56px;line-height:.8;color:var(--accent);letter-spacing:-.02em}
        .solx .sol-name{font-family:var(--sans);font-weight:700;font-size:22px;line-height:1.5;color:var(--ink);letter-spacing:-.01em}
        .solx .sol-name .hl{background:linear-gradient(transparent 58%, rgba(4,120,87,.22) 58%);padding:0 2px;
               -webkit-box-decoration-break:clone;box-decoration-break:clone}
        .solx .sol-body{font-size:15px;line-height:1.65;color:var(--sub);margin-top:9px}

        /* 푸터 */
        .foot{margin-top:56px;padding-top:22px;border-top:1px solid var(--hair);
             display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px;
             font-size:13px;color:var(--faint)}
        .foot b{color:var(--sub);font-weight:600}
        .foot .slug{font-family:var(--mono)}
        ${if (forPdf) "@page { size: A4; margin: 16mm; } .doc{max-width:none}" else ""}
    """.trimIndent()
}

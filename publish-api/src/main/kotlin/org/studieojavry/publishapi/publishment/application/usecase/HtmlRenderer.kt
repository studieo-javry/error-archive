package org.studieojavry.publishapi.publishment.application.usecase

import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import org.studieojavry.publishapi.publishment.domain.Visibility
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    private val KDATE_FMT = DateTimeFormatter.ofPattern("yyyy년 M월 d일")
    // 타임라인 좌측 날짜 — 발행연도와 같은 해면 MM.dd, 다른 해면 연도 포함
    private val STEP_DATE_FMT = DateTimeFormatter.ofPattern("MM.dd HH:mm")
    private val STEP_DATE_FMT_Y = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
    private val SNIPPET_MARKER = Regex("@snippet\\([A-Za-z0-9_-]+\\)")
    private val ATTACH_MARKER = Regex("@attach\\([A-Za-z0-9_-]+\\)")

    // 저자 바 공유 버튼 아이콘 (목업 그대로) — 링크 복사 / PDF 다운로드
    private const val ICON_LINK =
        "<svg fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.7\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M13.19 8.688a4.5 4.5 0 011.242 7.244l-4.5 4.5a4.5 4.5 0 01-6.364-6.364l1.757-1.757m13.35-.622l1.757-1.757a4.5 4.5 0 00-6.364-6.364l-4.5 4.5a4.5 4.5 0 001.242 7.244\"/></svg>"
    private const val ICON_PDF =
        "<svg fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.7\" viewBox=\"0 0 24 24\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3\"/></svg>"

    // 읽기 진행 바 (블로그 감성) — 웹 전용
    private val PROGRESS_JS = """
        <script>
        (function(){var pg=document.getElementById('progress');if(!pg)return;
        function on(){var h=document.documentElement,max=h.scrollHeight-h.clientHeight;
        pg.style.width=(max>0?(h.scrollTop/max*100):0)+'%';}
        document.addEventListener('scroll',on,{passive:true});on();})();
        </script>
    """.trimIndent()

    /** 대략적 읽기 시간(분) — 본문 텍스트 길이 / 500자, 최소 1. 코드/에러는 제외. */
    private fun readingMinutes(snap: ContentSnapshot): Int {
        val chars = buildString {
            snap.description?.let { append(stripMarkers(it)) }
            snap.steps.forEach { append(it.title ?: ""); append(it.body) }
            snap.solutions.forEach { append(it.title ?: ""); append(it.body) }
        }.length
        return maxOf(1, Math.round(chars / 500.0).toInt())
    }

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
            sb.appendLine("<link href=\"https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,500;9..144,600&family=Manrope:wght@400;500;600;700;800&family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&family=Nanum+Myeongjo:wght@400;700&display=swap\" rel=\"stylesheet\"/>")
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
        if (!forPdf) {
            sb.appendLine("<div class=\"progress\" id=\"progress\"></div>")
            sb.appendLine("<div class=\"topbar\"><span class=\"brand\"><span class=\"logo\">E</span>Error Archive <small>· 발행물</small></span></div>")
        }
        sb.appendLine("<div class=\"wrap\">")

        // ── 헤더 ──
        sb.appendLine("<header class=\"hd\">")
        // Resolved 배지(Solid) — 맨 위 가운데
        if (snap.solutions.isNotEmpty()) {
            sb.appendLine("<div class=\"rbadge-wrap\"><span class=\"rbadge\"><span class=\"ck\">✓</span>Resolved</span></div>")
        }
        sb.appendLine("<h1 class=\"title\">${esc(p.title)}</h1>")
        // 태그 — 제목 아래 좌측, 모노 텍스트를 ` · ` 로 연결
        run {
            val cats = snap.tags.filter { it.isNotBlank() }
            if (cats.isNotEmpty()) {
                val inner = cats.joinToString("<span class=\"d\">·</span>") { "<span>${esc(it)}</span>" }
                sb.appendLine("<div class=\"hdtags\">$inner</div>")
            }
        }
        // 요약 = TL;DR (밑줄 헤딩)
        p.summary?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine("<div class=\"tldr\"><div class=\"lb\">TL;DR</div><p class=\"t\">${inlineMd(it)}</p></div>")
        }
        // 저자 바 (아바타 + 이름/메타 + 공유 버튼: 링크복사·PDF)
        val author = opts.authorDisplayName?.takeIf { it.isNotBlank() } ?: "익명"
        val initial = author.trim().firstOrNull()?.toString() ?: "·"
        sb.appendLine("<div class=\"byline\">")
        sb.appendLine("<div class=\"avatar\">${esc(initial)}</div>")
        sb.appendLine("<div class=\"who\">")
        sb.appendLine("<div class=\"n\">${esc(author)}</div>")
        sb.appendLine("<div class=\"meta\"><b>${KDATE_FMT.format(p.publishedAt)}</b> · ${readingMinutes(snap)}분 읽기</div>")
        sb.appendLine("</div>")
        if (!forPdf) {
            sb.appendLine("<div class=\"share\">")
            sb.appendLine("<button type=\"button\" title=\"링크 복사\" onclick=\"navigator.clipboard&amp;&amp;navigator.clipboard.writeText(location.href)\">$ICON_LINK</button>")
            sb.appendLine("<a href=\"/p/${esc(p.slug)}.pdf\" title=\"PDF 다운로드\">$ICON_PDF</a>")
            sb.appendLine("</div>")
        }
        sb.appendLine("</div>")
        sb.appendLine("</header>")

        sb.appendLine("<article>")

        // ── 인트로 = Context(description), 제목 없이 본문 첫 문단 ──
        val descText = snap.description?.let { stripMarkers(it) }?.takeIf { it.isNotBlank() }
        if (descText != null) renderProse(sb, descText, opts, forPdf)
        snap.descriptionSnippets.forEach { renderSnippet(sb, it, opts, forPdf) }
        snap.descriptionAttachments.forEach { att -> renderAttachment(sb, att, p.slug, forPdf, publicBaseUrl, imageEmbedUrl) }

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
                sb.appendLine("<div class=\"sec-head\"><span class=\"sec-eyebrow\">The Error</span><h2 class=\"sec\">What Broke</h2></div>")
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

        // ── The Journey (K3 에디토리얼 타임라인) ──
        if (snap.steps.isNotEmpty()) {
            sb.appendLine("<section class=\"section\">")
            sb.appendLine("<div class=\"sec-head\"><span class=\"sec-eyebrow\">The Journey</span><h2 class=\"sec\">Chasing the Cause</h2></div>")
            sb.appendLine("<div class=\"tl\">")
            snap.steps.forEachIndexed { i, st ->
                renderStep(sb, i + 1, st, opts, p.slug, p.publishedAt.year, snap.originalCaseCreatedAt, forPdf, publicBaseUrl, imageEmbedUrl)
            }
            sb.appendLine("</div>")
            sb.appendLine("</section>")
        }

        // ── Solution (체크 노드 마커 + 카피) ──
        if (snap.solutions.isNotEmpty()) {
            sb.appendLine("<section class=\"section\">")
            sb.appendLine("<div class=\"sec-head\"><span class=\"sec-eyebrow\">The Fix</span><h2 class=\"sec\">The Resolution</h2></div>")
            snap.solutions.forEachIndexed { i, sol ->
                val name = sol.title?.takeIf { it.isNotBlank() } ?: "해결 ${i + 1}"
                sb.appendLine("<div class=\"solution-block\">")
                sb.appendLine("<div class=\"solution-mark\" aria-hidden=\"true\"><span class=\"sol-index\">${(i + 1).toString().padStart(2, '0')}</span><span class=\"sol-check\">✓</span></div>")
                sb.appendLine("<div class=\"solution-copy\">")
                sb.appendLine("<div class=\"solution-kicker\"><span class=\"solution-label\">Final resolution</span><span class=\"solution-status\">Verified</span></div>")
                sb.appendLine("<h3 class=\"solution-title\">${esc(name)}</h3>")
                if (sol.body.isNotBlank()) sb.appendLine("<p class=\"solution-body\">${inlineMd(sol.body)}</p>")
                sb.appendLine("</div>")
                sb.appendLine("</div>")
                sol.snippets.forEach { renderSnippet(sb, it, opts, forPdf) }
            }
            sb.appendLine("</section>")
        }

        sb.appendLine("</article>")

        // ── 푸터 ──
        sb.appendLine("<footer class=\"ft\">")
        sb.appendLine("<div class=\"pub\"><span class=\"logo\">E</span><div><div class=\"t\">Published with Error Archive</div><div class=\"s\">에러를 기록하고, 해결을 공유하세요</div></div></div>")
        sb.appendLine("<div class=\"stats\"><span>${p.viewCount} views</span><span>/p/${esc(p.slug)}</span></div>")
        sb.appendLine("</footer>")

        sb.appendLine("</div>") // .wrap
        if (!forPdf) sb.appendLine(PROGRESS_JS)
        sb.appendLine("</body></html>")
        return sb.toString()
    }

    /**
     * K3 에디토리얼 타임라인 스텝 — 좌측 상태 컬럼(word + 날짜) + 아이콘 dot(·/✕/✓) + 본문.
     * 상태(class·word·icon)는 BE StepStatus(RESOLVED/IN_PROGRESS/FAILED)에서 매핑. null → neutral.
     * 날짜는 MM.DD HH:mm(발행연도와 같은 해) 또는 yyyy.MM.dd HH:mm(다른 해).
     */
    private fun renderStep(
        sb: StringBuilder,
        num: Int,
        st: ContentSnapshot.StepDoc,
        opts: PublishOptions,
        slug: String,
        pubYear: Int,
        caseCreatedAt: LocalDateTime,
        forPdf: Boolean,
        publicBaseUrl: String,
        imageEmbedUrl: (String) -> String?,
    ) {
        val (statusClass, word, icon) = when (st.outcome) {
            "RESOLVED" -> Triple("ok", "Resolved", "✓")
            "FAILED" -> Triple("fail", "Failed", "✕")
            "IN_PROGRESS" -> Triple("wip", "In&nbsp;Progress", "·")
            else -> Triple("neutral", "Step", "·")
        }
        val dateTxt = stepDateLabel(opts.timeStyle, st.occurredAt, pubYear, caseCreatedAt)
        val durTxt = if (opts.showStepDuration && st.durationMinutes != null && st.durationMinutes > 0)
            DurationFormat.humanize(st.durationMinutes) else null
        val title = st.title?.takeIf { it.isNotBlank() } ?: "Step $num"

        sb.appendLine("<div class=\"st $statusClass\">")
        sb.append("<div class=\"st-left\"><span class=\"word\">$word</span>")
        if (dateTxt != null) sb.append("<span class=\"st-date\">${esc(dateTxt)}</span>")
        sb.appendLine("</div>")
        sb.appendLine("<div class=\"dot\">$icon</div>")
        sb.appendLine("<div class=\"st-head\">")
        sb.appendLine("<div class=\"st-title-group\"><span class=\"st-kicker\">Step ${num.toString().padStart(2, '0')}</span><h3 class=\"st-title\">${esc(title)}</h3></div>")
        if (durTxt != null) sb.appendLine("<span class=\"st-meta\">${esc(durTxt)}</span>")
        sb.appendLine("</div>")
        if (st.body.isNotBlank()) {
            renderProse(sb, stripMarkers(st.body), opts, forPdf)
        }
        st.snippets.forEach { renderSnippet(sb, it, opts, forPdf) }
        st.attachments.forEach { att -> renderAttachment(sb, att, slug, forPdf, publicBaseUrl, imageEmbedUrl) }
        sb.appendLine("</div>")
    }

    /** 스니펫 코드블록(다크) — 상단 랭귀지 탭 + 신호등 점. 줄 번호(codeLineNumbers·웹 전용)는 `.numbered`. */
    private fun renderSnippet(sb: StringBuilder, sn: ContentSnapshot.SnippetDoc, opts: PublishOptions, forPdf: Boolean) {
        sn.title?.takeIf { it.isNotBlank() }?.let { sb.appendLine("<div class=\"snip-title\">${esc(it)}</div>") }
        renderCodeBlock(sb, sn.language, sn.code, opts, forPdf)
        sn.caption?.takeIf { it.isNotBlank() }?.let { sb.appendLine("<div class=\"cap\">${esc(it)}</div>") }
    }

    /** 다크 코드박스 렌더(랭귀지 탭 + 신호등 + 하이라이팅). 스니펫/본문 펜스 블록 공용. */
    private fun renderCodeBlock(sb: StringBuilder, language: String, code: String, opts: PublishOptions, forPdf: Boolean) {
        val numCls = if (opts.codeLineNumbers && !forPdf) " numbered" else ""
        val langLabel = language.ifBlank { "code" }.lowercase()
        sb.appendLine("<div class=\"codewrap\">")
        sb.appendLine("<div class=\"code-bar\"><span class=\"lang\">${esc(langLabel)}</span><span class=\"dots\"><i></i><i></i><i></i></span></div>")
        sb.appendLine("<pre class=\"code$numCls\"><code>${highlightCode(code, language)}</code></pre>")
        sb.appendLine("</div>")
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

    /**
     * 타임라인 좌측 컬럼 날짜. TimeStyle.NONE 이면 숨김. 그 외에는 발행연도 기준으로
     * 같은 해면 `MM.dd HH:mm`, 다른 해면 `yyyy.MM.dd HH:mm`. (하루에 스텝 여러 개 가능 → Day N 폐기.)
     */
    private fun stepDateLabel(style: PublishOptions.TimeStyle, occurredAt: LocalDateTime?, pubYear: Int, caseCreatedAt: LocalDateTime): String? {
        if (occurredAt == null || style == PublishOptions.TimeStyle.NONE) return null
        val fmt = if (occurredAt.year == pubYear) STEP_DATE_FMT else STEP_DATE_FMT_Y
        return fmt.format(occurredAt)
    }

    // `@snippet(x)`/`@attach(x)` 마커만 제거. 공백은 collapse 하지 않는다
    // (펜스 코드블록 들여쓰기 보존 — prose 의 이중 공백은 HTML 이 알아서 collapse).
    private fun stripMarkers(text: String): String =
        text.replace(SNIPPET_MARKER, "").replace(ATTACH_MARKER, "").trim()

    // 구문 하이라이팅 — 경량 토크나이저. 다언어 공통 키워드(과도한 오탐 방지 위해 핵심만).
    private val HL_KEYWORDS = setOf(
        "abstract", "actual", "as", "async", "await", "break", "case", "catch", "class", "companion",
        "const", "constructor", "continue", "data", "def", "default", "do", "elif", "else", "enum",
        "expect", "export", "extends", "external", "false", "final", "finally", "for", "fun", "func",
        "function", "if", "impl", "implements", "import", "in", "init", "inline", "interface", "is",
        "lateinit", "let", "match", "new", "null", "object", "open", "operator", "override", "package",
        "pass", "private", "protected", "public", "raise", "record", "reified", "return", "sealed",
        "static", "struct", "super", "suspend", "switch", "this", "throw", "throws", "trait", "true",
        "try", "typealias", "typeof", "union", "use", "val", "var", "void", "when", "while", "yield",
    )
    // `#` 를 라인 주석으로 쓰는 언어(그 외는 `//` + `/* */`).
    private val HASH_COMMENT_LANGS = setOf(
        "python", "py", "ruby", "rb", "shell", "bash", "sh", "zsh", "yaml", "yml", "toml",
        "dockerfile", "docker", "makefile", "make", "r", "perl", "pl", "ini", "conf", "properties",
    )

    /**
     * 코드를 줄 단위 span(`.cl`) + 토큰 span(`.tk-*`)으로. 줄 번호 CSS 의 counter-increment 대상은 `.cl`.
     * 블록 주석(`/* */`)은 줄 사이 상태를 이어가고, 문자열/문자열은 줄 단위로 처리(스니펫 특성상 충분).
     * 모든 출력 텍스트는 [esc] 로 이스케이프.
     */
    private fun highlightCode(code: String, language: String): String {
        val hashComment = language.lowercase() in HASH_COMMENT_LANGS
        val sb = StringBuilder()
        var inBlock = false
        for (line in code.removeSuffix("\n").split("\n")) {
            val (html, next) = highlightLine(line, inBlock, hashComment)
            sb.append("<span class=\"cl\">").append(html).append("</span>")
            inBlock = next
        }
        return sb.toString()
    }

    /** 한 줄 하이라이트. 반환: (HTML, 다음 줄로 이어질 블록주석 상태). */
    private fun highlightLine(line: String, startInBlock: Boolean, hashComment: Boolean): Pair<String, Boolean> {
        val slash = !hashComment
        val out = StringBuilder()
        val n = line.length
        var i = 0
        var inBlock = startInBlock
        fun tk(cls: String, text: String) =
            out.append("<span class=\"").append(cls).append("\">").append(esc(text)).append("</span>")

        if (inBlock) {
            val end = line.indexOf("*/")
            if (end < 0) { if (line.isNotEmpty()) tk("tk-c", line); return out.toString() to true }
            tk("tk-c", line.substring(0, end + 2)); i = end + 2; inBlock = false
        }
        while (i < n) {
            val c = line[i]
            if (slash && c == '/' && i + 1 < n && line[i + 1] == '*') {
                val end = line.indexOf("*/", i + 2)
                if (end < 0) { tk("tk-c", line.substring(i)); return out.toString() to true }
                tk("tk-c", line.substring(i, end + 2)); i = end + 2; continue
            }
            if (slash && c == '/' && i + 1 < n && line[i + 1] == '/') { tk("tk-c", line.substring(i)); break }
            if (hashComment && c == '#') { tk("tk-c", line.substring(i)); break }
            if (c == '"' || c == '\'' || c == '`') {
                val start = i; i++
                while (i < n) {
                    if (line[i] == '\\' && i + 1 < n) { i += 2; continue }
                    if (line[i] == c) { i++; break }
                    i++
                }
                tk("tk-s", line.substring(start, i)); continue
            }
            if (c == '@' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '_')) {
                val start = i; i++
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                tk("tk-f", line.substring(start, i)); continue
            }
            if (c.isLetter() || c == '_' || c == '$') {
                val start = i
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '$')) i++
                val word = line.substring(start, i)
                var j = i
                while (j < n && line[j] == ' ') j++
                when {
                    word in HL_KEYWORDS -> tk("tk-k", word)
                    j < n && line[j] == '(' -> tk("tk-f", word)
                    word[0].isUpperCase() -> tk("tk-t", word)
                    else -> out.append(esc(word))
                }
                continue
            }
            out.append(esc(c.toString())); i++
        }
        return out.toString() to inBlock
    }

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
     * 블록 프로즈 렌더. 빈 줄로 문단 분리, ```` ``` ```` 펜스는 다크 코드박스로,
     * `>` 로 시작하는 줄(들)은 blockquote 로 묶는다.
     * 각 조각 텍스트는 [inlineMd] 로(인라인 코드 + 줄바꿈) 처리. XSS 는 esc 로 차단.
     */
    private fun renderProse(sb: StringBuilder, raw: String, opts: PublishOptions, forPdf: Boolean) {
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
                line.trimStart().startsWith("```") -> {
                    flushPara()
                    // 여는 펜스 뒤 텍스트 = 언어. 닫는 ``` (또는 EOF) 까지 코드로 수집.
                    val lang = line.trimStart().removePrefix("```").trim()
                    i++
                    val code = StringBuilder()
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        if (code.isNotEmpty()) code.append("\n")
                        code.append(lines[i]); i++
                    }
                    if (i < lines.size) i++ // 닫는 펜스 소비
                    renderCodeBlock(sb, lang, code.toString(), opts, forPdf)
                }
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

    /* ── styles (확정 목업 publish-article-blog · sol-c 합본 · 배경 #f7f7f5) ────── */
    private fun styles(forPdf: Boolean): String = """
        :root{
          --paper:#f7f7f5; --ink:#1a1815; --sub:#5c5852; --faint:#a8a29a; --hair:#eceae5; --hair-2:#f4f2ee;
          --accent:#047857; --accent-ink:#065f46; --accent-soft:#ecfdf5;
          --rail:#e2ded6;
          --code-bg:#0e1116; --code-ink:#e7edf3; --code-line:#20262e; --code-gutter:#4b5563;
          --serif-disp:'Fraunces','Nanum Myeongjo','Noto Sans KR',Georgia,serif;
          --sans:'Inter','Pretendard Variable',Pretendard,'Noto Sans KR',system-ui,sans-serif;
          --mono:'JetBrains Mono','Noto Sans KR',ui-monospace,Menlo,monospace;
          --body:'Manrope','Pretendard Variable',Pretendard,'Noto Sans KR',system-ui,sans-serif;
        }
        *{box-sizing:border-box;margin:0}
        body{background:var(--paper);color:var(--ink);font-family:var(--sans);line-height:1.6;
             -webkit-font-smoothing:antialiased;-moz-osx-font-smoothing:grayscale}

        /* 읽기 진행 바 + 상단 바 (웹 전용 요소) */
        .progress{position:fixed;top:0;left:0;height:2.5px;width:0;background:linear-gradient(90deg,var(--accent),#10b981);z-index:60;transition:width .08s linear}
        .topbar{position:sticky;top:0;z-index:50;display:flex;align-items:center;gap:12px;
             padding:11px 24px;background:color-mix(in srgb,var(--paper) 82%,transparent);backdrop-filter:blur(10px);border-bottom:1px solid var(--hair)}
        .brand{display:inline-flex;align-items:center;gap:8px;font:600 13px var(--sans);color:var(--ink)}
        .brand .logo{width:22px;height:22px;border-radius:7px;background:var(--accent);color:#fff;display:grid;place-items:center;font-weight:700;font-size:12px}
        .brand small{color:var(--faint);font-weight:400}

        .wrap{max-width:720px;margin:0 auto;padding:0 24px}

        /* 헤더 */
        header.hd{padding:${if (forPdf) "8px 0 0" else "60px 0 0"}}
        /* Resolved 배지(Solid) — 맨 위 가운데 */
        .rbadge-wrap{text-align:center;margin-bottom:24px}
        .rbadge{display:inline-flex;align-items:center;gap:7px;color:#fff;background:var(--accent);
                font:600 11px var(--mono);letter-spacing:.09em;text-transform:uppercase;
                padding:5px 13px 5px 10px;border-radius:999px;box-shadow:0 4px 12px rgba(4,120,87,.22)}
        .rbadge .ck{font-size:11px}
        /* 태그 — 제목 아래 좌측, 모노 텍스트를 · 로 연결 */
        .hdtags{display:flex;flex-wrap:wrap;align-items:center;gap:0;margin-top:14px;
                font:600 10.5px var(--mono);letter-spacing:.06em;text-transform:uppercase;color:var(--sub)}
        .hdtags .d{margin:0 8px;color:var(--faint);opacity:.6}
        h1.title{font-family:var(--serif-disp);font-weight:600;font-size:${if (forPdf) "32px" else "clamp(27px,3.8vw,36px)"};line-height:1.15;letter-spacing:-.02em;text-wrap:balance;color:var(--ink)}
        .tldr{margin-top:20px}
        .tldr .lb{font-family:var(--body);font-weight:800;font-size:13px;letter-spacing:-.01em;color:var(--ink);position:relative;display:inline-block;margin-bottom:10px;padding-bottom:3px}
        .tldr .lb::after{content:"";position:absolute;left:0;right:0;bottom:0;height:2px;background:var(--accent)}
        .tldr .t{font-family:var(--body);font-size:16px;line-height:1.62;color:var(--sub)}
        .tldr .t code{font-family:var(--mono);font-size:.84em;background:var(--hair-2);border:1px solid var(--hair);padding:1px 5px;border-radius:5px;color:#9a3412}
        .byline{display:flex;align-items:center;gap:13px;margin:30px 0 0;padding:20px 0;border-top:1px solid var(--hair);border-bottom:1px solid var(--hair)}
        .avatar{width:46px;height:46px;border-radius:50%;background:linear-gradient(135deg,var(--accent),#0ea5e9);color:#fff;display:grid;place-items:center;font:600 17px var(--sans);flex-shrink:0}
        .who{flex:1;min-width:0}
        .who .n{font:600 15px var(--sans);color:var(--ink);display:flex;align-items:center;gap:7px}
        .who .n .hd{font:400 13px var(--mono);color:var(--faint)}
        .who .meta{font-size:13.5px;color:var(--faint);margin-top:2px}
        .who .meta b{color:var(--sub);font-weight:500}
        .share{display:flex;gap:6px}
        .share button,.share a{width:34px;height:34px;border-radius:50%;border:1px solid var(--hair);background:#fff;color:var(--sub);display:grid;place-items:center;cursor:pointer;transition:.15s;text-decoration:none}
        .share button:hover,.share a:hover{border-color:var(--accent);color:var(--accent)}
        .share svg{width:16px;height:16px}

        /* 본문 */
        article{font-family:var(--body);font-size:16px;line-height:1.75;color:#26221d;padding:38px 0 0}
        article p,p{margin:0 0 26px;font-size:16px;line-height:1.75;color:#26221d}
        article a,a{color:var(--accent-ink);text-underline-offset:3px}
        strong,b{font-weight:600;color:var(--ink)}
        code{font-family:var(--mono);font-size:.82em;background:var(--hair-2);border:1px solid var(--hair);padding:1px 6px;border-radius:5px;color:#9a3412}
        /* 코드블록(pre) 안의 code 는 인라인 코드 칩 스타일을 상속하면 안 된다(전체가 빨갛게 보이는 버그) */
        pre.code code{background:none;border:0;padding:0;border-radius:0;color:inherit;font-size:inherit}
        /* 구문 하이라이팅 토큰(다크 코드 배경 기준, GitHub 계열 팔레트) */
        pre.code .tk-k{color:#ff7b72}
        pre.code .tk-s{color:#a5d6ff}
        pre.code .tk-c{color:#8b949e;font-style:italic}
        pre.code .tk-f{color:#d2a8ff}
        pre.code .tk-t{color:#7ee787}
        .section{margin:0}
        /* 섹션 헤더: 상단 얇은 룰 + eyebrow(라벨) + 세리프 제목. 헤더 아래 요소는 top-margin 을
           무시해 간격을 .sec-head 하단 여백(20px)으로 통일. */
        .sec-head{margin:46px 0 20px;padding-top:18px;border-top:1px solid var(--hair)}
        .section > .sec-head + *{margin-top:0}
        .sec-eyebrow{display:block;margin-bottom:6px;color:var(--accent);font:600 11px var(--mono);letter-spacing:.1em;text-transform:uppercase}
        h2.sec{margin:0;color:var(--ink);font-family:var(--serif-disp);font-size:22px;font-weight:600;line-height:1.3;letter-spacing:-.01em}

        /* Error 터미널 창 */
        .crash{margin:8px 0 30px;border:1px solid var(--code-line);border-radius:12px;overflow:hidden;background:var(--code-bg)}
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
        .snip-title{font-family:var(--sans);font-weight:600;font-size:13px;margin:6px 0 4px;color:var(--sub)}
        .codewrap{margin:8px 0 30px;border:1px solid var(--code-line);border-radius:12px;overflow:hidden;background:var(--code-bg)}
        .code-bar{display:flex;align-items:center;justify-content:space-between;padding:9px 14px;background:rgba(255,255,255,.03);border-bottom:1px solid var(--code-line)}
        .code-bar .lang{font:600 11px var(--mono);letter-spacing:.06em;text-transform:uppercase;color:#8b949e}
        .code-bar .dots{display:flex;gap:6px}
        .code-bar .dots i{width:10px;height:10px;border-radius:50%;background:#30363d;display:block}
        pre.code{margin:0;padding:15px 18px;overflow-x:auto;color:var(--code-ink);font:13px/1.7 var(--mono)}
        pre.code .cl{display:block;white-space:pre}
        pre.code.numbered code{counter-reset:ln}
        pre.code.numbered .cl{counter-increment:ln;position:relative;padding-left:3.2em}
        pre.code.numbered .cl::before{content:counter(ln);position:absolute;left:0;top:0;width:2.4em;text-align:right;
               padding-right:.8em;color:var(--code-gutter);border-right:1px solid var(--code-line);-webkit-user-select:none;user-select:none}
        .cap{font-family:var(--sans);font-size:13px;color:var(--faint);margin:8px 2px 0;font-style:italic}

        /* 인용 — GitHub md 스타일(본문 폰트 + 회색 톤) */
        blockquote{margin:26px 0;padding:2px 0 2px 18px;border-left:3px solid #dcd8d1;
                  font-family:var(--body);font-weight:400;font-size:16px;line-height:1.65;color:#6f6a63}

        /* The Journey — K3 에디토리얼 타임라인 (좌측 상태 컬럼 + 아이콘 dot + 얇은 rail) */
        .tl{position:relative;margin-top:6px}
        .st{position:relative;padding-bottom:44px;padding-left:132px}
        .st:last-child{padding-bottom:0}
        .st.ok{--c:var(--accent);--dot:#10b981}
        .st.wip{--c:#6b7280;--dot:#9ca3af}
        .st.fail{--c:#e11d48;--dot:#fb3b5c}
        .st.neutral{--c:#9b968d;--dot:#c2bcb1}
        .st:not(:last-child)::before{content:"";position:absolute;top:20px;bottom:-24px;left:109px;width:1px;background:var(--rail)}
        .st .dot{position:absolute;top:4px;left:104px;z-index:1;display:grid;width:11px;height:11px;place-items:center;
                 border-radius:50%;background:var(--dot);color:#fff;font:700 7px/1 var(--mono)${if (forPdf) "" else ";box-shadow:0 0 0 2.5px color-mix(in srgb,var(--dot) 13%,transparent)"}}
        ${if (forPdf) "" else """.st.ok .dot{box-shadow:0 0 0 3px color-mix(in srgb,var(--dot) 20%,transparent),0 0 9px 1px color-mix(in srgb,var(--dot) 50%,transparent),0 0 16px 3px color-mix(in srgb,var(--dot) 30%,transparent)}"""}
        .st .st-left{position:absolute;top:1px;left:0;width:92px;text-align:right}
        .st .st-left .word{display:block;color:var(--c);font:600 11px/1.3 var(--mono);letter-spacing:.08em;text-transform:uppercase}
        .st .st-left .st-date{display:block;margin-top:5px;color:var(--faint);font:400 10px var(--mono)}
        .st .st-head{display:flex;align-items:flex-start;gap:11px;min-height:18px;margin-bottom:13px}
        .st-title-group{min-width:0}
        .st .st-kicker{display:block;margin-bottom:5px;color:var(--accent);font:600 9px var(--mono);letter-spacing:.13em;text-transform:uppercase}
        .st h3.st-title{margin:0;color:var(--ink);font:600 16.5px/1.4 var(--body);letter-spacing:-.012em}
        .st .st-meta{margin-left:auto;padding-top:22px;padding-left:12px;color:var(--faint);font:400 11.5px var(--mono);letter-spacing:.01em;white-space:nowrap}
        .st p{margin:0 0 12px}

        /* 첨부 */
        .att{font-size:13px;color:var(--sub);margin:10px 0;font-family:var(--mono)}
        .att a{color:var(--accent-ink)}
        .att .att-act{font-size:11px;color:var(--faint);margin-left:4px}
        .att.img{max-width:100%;border-radius:10px;border:1px solid var(--hair);display:block;margin:12px 0}

        /* Solution — 체크 노드 마커 + 카피 (배경 면 없음) */
        .solution-block{display:grid;grid-template-columns:32px 1fr;gap:16px;align-items:start;margin:22px 0 24px}
        .solution-mark .sol-index{display:none}
        .solution-mark .sol-check{display:grid;width:32px;height:32px;place-items:center;border-radius:50%;
               background:var(--accent);color:#fff;font:600 15px var(--sans)${if (forPdf) "" else ";box-shadow:0 6px 16px rgba(4,120,87,.18)"}}
        .solution-kicker{display:flex;align-items:center;gap:9px;margin-bottom:9px;padding-top:6px;font:600 9px var(--mono);letter-spacing:.11em;text-transform:uppercase}
        .solution-label{color:var(--accent)}
        .solution-status{display:inline-flex;align-items:center;gap:5px;padding:3px 8px;border-radius:999px;
               background:var(--accent-soft);color:var(--accent-ink);font-size:9px;letter-spacing:.06em}
        .solution-status::before{content:"";width:5px;height:5px;border-radius:50%;background:#10b981${if (forPdf) "" else ";box-shadow:0 0 7px rgba(16,185,129,.45)"}}
        .solution-title{margin:0;color:var(--ink);font:600 21px/1.4 var(--body);letter-spacing:-.018em;text-wrap:balance}
        .solution-copy .solution-body{margin:10px 0 0;color:var(--sub);font:400 15px/1.68 var(--body)}
        .solution-copy .solution-body code{font-family:var(--mono);font-size:.84em;background:var(--hair-2);border:1px solid var(--hair);padding:1px 5px;border-radius:5px;color:#9a3412}

        /* 푸터 */
        footer.ft{margin:40px 0 0;padding:${if (forPdf) "26px 0 0" else "26px 0 90px"};border-top:1px solid var(--hair);
             display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:16px}
        .ft .pub{display:flex;align-items:center;gap:10px}
        .ft .pub .logo{width:30px;height:30px;border-radius:9px;background:var(--accent);color:#fff;display:grid;place-items:center;font:700 14px var(--sans)}
        .ft .pub .t{font:600 14px var(--sans);color:var(--ink)}
        .ft .pub .s{font-size:12.5px;color:var(--faint)}
        .ft .stats{display:flex;gap:16px;font:400 13px var(--mono);color:var(--faint)}
        ${if (forPdf) "" else """
        /* 좁은 화면 — 타임라인 좌측 컬럼을 위로 접어 올림 */
        @media(max-width:560px){
          .wrap{padding:0 19px}
          header.hd{padding-top:42px}
          .topbar{padding-inline:19px}
          .st{padding-left:0}
          .st:not(:last-child)::before,.st .dot{display:none}
          .st .st-left{position:static;display:flex;align-items:baseline;gap:10px;width:auto;margin-bottom:6px;text-align:left}
          .st .st-left .st-date{margin-top:0}
          .st .st-meta{padding-top:4px}
        }"""}
        ${if (forPdf) "@page{size:A4;margin:16mm}.wrap{max-width:none;padding:0}" else ""}
    """.trimIndent()
}

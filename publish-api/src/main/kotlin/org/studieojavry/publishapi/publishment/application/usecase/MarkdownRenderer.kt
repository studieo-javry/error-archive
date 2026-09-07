package org.studieojavry.publishapi.publishment.application.usecase

import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.publishment.domain.ContentSnapshot
import org.studieojavry.publishapi.publishment.domain.PublishOptions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * `CasePublishment` → 마크다운 직렬화.
 *
 * 코드 스니펫은 ``` fenced block, 첨부는 kind 기반(이미지 인라인 / 그 외 링크).
 * 본문의 `@snippet(x)` / `@attach(x)` 토큰은 step 자식으로 모았으므로 *제거* 후 자연 텍스트.
 * MD 는 박제 산출물이라 첨부 링크는 무만료 **안정 라우트 절대 URL**(publicBaseUrl) 사용.
 */
object MarkdownRenderer {

    private val DATE_FMT = DateTimeFormatter.ISO_DATE
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
    private val EXACT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")   // 연도 포함 — HtmlRenderer 와 동일
    private val SNIPPET_MARKER = Regex("@snippet\\([A-Za-z0-9_-]+\\)")
    private val ATTACH_MARKER = Regex("@attach\\([A-Za-z0-9_-]+\\)")

    fun render(p: CasePublishment, publicBaseUrl: String = ""): String {
        val sb = StringBuilder()
        val snap = p.contentSnapshot
        val opts = p.options

        sb.appendLine("# ${p.title}")
        sb.appendLine()
        sb.appendLine("> ${p.summary ?: ""}".trimEnd())
        sb.appendLine()

        // Front matter — 메타
        opts.authorDisplayName?.let { sb.appendLine("**by** $it  ") }
        sb.appendLine("**published** ${p.publishedAt.toLocalDate().format(DATE_FMT)}  ")
        if (snap.tags.isNotEmpty()) {
            sb.appendLine("**tags** " + snap.tags.joinToString(" ") { "`$it`" })
        }
        sb.appendLine()

        // Error snapshot (선택적)
        snap.snapshot?.let { es ->
            sb.appendLine("## Error")
            es.exceptionClass?.let { sb.appendLine("**`$it`**") }
            es.exceptionMessage?.let { sb.appendLine(); sb.appendLine("> ${it.replace("\n", " ")}") }
            if (!es.rawStackTrace.isNullOrBlank()) {
                sb.appendLine()
                sb.appendLine("```")
                sb.appendLine(es.rawStackTrace.lineSequence().take(20).joinToString("\n"))
                sb.appendLine("```")
            }
            sb.appendLine()
        }

        // Description (Context) — 마커 제거 텍스트 + 참조 자산 (C1, 스텝과 동일 패턴)
        val descText = snap.description?.let { stripMarkers(it) }?.takeIf { it.isNotBlank() }
        if (descText != null || snap.descriptionSnippets.isNotEmpty() || snap.descriptionAttachments.isNotEmpty()) {
            sb.appendLine("## Context")
            if (descText != null) { sb.appendLine(descText); sb.appendLine() }
            snap.descriptionSnippets.forEach { mdSnippet(sb, it) }
            snap.descriptionAttachments.forEach { mdAttachment(sb, it, p.slug, publicBaseUrl) }
            sb.appendLine()
        }

        // Steps — the journey
        if (snap.steps.isNotEmpty()) {
            sb.appendLine("## The Journey")
            sb.appendLine()
            snap.steps.forEachIndexed { i, step ->
                renderStep(sb, i + 1, step, opts, p.slug, snap.originalCaseCreatedAt, publicBaseUrl)
            }
        }

        // Solutions
        if (snap.solutions.isNotEmpty()) {
            sb.appendLine("## Solution")
            sb.appendLine()
            snap.solutions.forEach { sol ->
                sol.title?.let { sb.appendLine("### $it") }
                if (sol.body.isNotBlank()) sb.appendLine(sol.body)
                sb.appendLine()
            }
        }

        sb.appendLine("---")
        sb.appendLine("_Published with **Error Archive** · `${p.slug}`_")
        return sb.toString()
    }

    private fun renderStep(
        sb: StringBuilder,
        num: Int,
        step: ContentSnapshot.StepDoc,
        opts: PublishOptions,
        slug: String,
        caseCreatedAt: LocalDateTime,
        publicBaseUrl: String,
    ) {
        val outcomeIcon = when (step.outcome) {
            "RESOLVED" -> "✅"
            "FAILED"   -> "❌"
            "IN_PROGRESS" -> "⏳"
            else -> "•"
        }
        val title = step.title?.takeIf { it.isNotBlank() } ?: "Step $num"
        sb.append("### $outcomeIcon Step $num — $title")
        stepTimeLabel(opts.timeStyle, step.occurredAt, caseCreatedAt)?.let { sb.append("  `$it`") }
        if (opts.showStepDuration && step.durationMinutes != null && step.durationMinutes > 0) {
            sb.append("  *(${DurationFormat.humanize(step.durationMinutes)})*")
        }
        sb.appendLine()
        sb.appendLine()

        if (step.body.isNotBlank()) {
            sb.appendLine(stripMarkers(step.body))
            sb.appendLine()
        }

        step.snippets.forEach { mdSnippet(sb, it) }
        step.attachments.forEach { mdAttachment(sb, it, slug, publicBaseUrl) }
    }

    /** 스니펫 fenced block — 스텝/description 공용. */
    private fun mdSnippet(sb: StringBuilder, sn: ContentSnapshot.SnippetDoc) {
        sn.title?.takeIf { it.isNotBlank() }?.let { sb.appendLine("**${it}**") }
        sb.appendLine("```${sn.language.lowercase()}")
        sb.appendLine(sn.code)
        sb.appendLine("```")
        sn.caption?.takeIf { it.isNotBlank() }?.let { sb.appendLine("_${it}_"); sb.appendLine() }
        sb.appendLine()
    }

    /** 첨부 — IMAGE 인라인 / 그 외 링크. 무만료 안정 라우트 절대 URL. 스텝/description 공용. */
    private fun mdAttachment(sb: StringBuilder, att: ContentSnapshot.AttachmentRef, slug: String, publicBaseUrl: String) {
        if (att.kind == "IMAGE") {
            val href = AttachmentRender.fileHref(publicBaseUrl, slug, att.markerId, "inline")
            sb.appendLine("![${att.fileName}]($href)")
        } else {
            val disp = AttachmentRender.effectiveDisposition("inline", att.contentType)
            val href = AttachmentRender.fileHref(publicBaseUrl, slug, att.markerId, disp)
            sb.appendLine("📎 [${att.fileName}]($href)")
        }
        sb.appendLine()
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
}

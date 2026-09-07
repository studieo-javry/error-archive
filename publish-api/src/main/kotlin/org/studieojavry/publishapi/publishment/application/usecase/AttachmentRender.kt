package org.studieojavry.publishapi.publishment.application.usecase

/**
 * 첨부 표현 규칙 — 안정 파일 라우트(`/p/{slug}/files/{markerId}?disposition=`)와 렌더러가 공유.
 *
 *  - 표현은 kind 가 아니라 **contentType(브라우저 인라인 렌더 가능 여부)** 로 결정.
 *  - inline 로 열면 브라우저 네이티브 뷰어(텍스트/JSON/PDF/이미지)가 뜬다. 그 외는 다운로드.
 *  - HTML/SVG 는 렌더 가능해도 XSS 위험이라 강제 다운로드(allowlist 에서 제외).
 */
object AttachmentRender {

    /** inline 새 탭으로 브라우저가 안전하게 렌더할 수 있는 contentType 인가. */
    fun isInlineViewable(contentType: String?): Boolean {
        val ct = contentType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        if (ct.isBlank()) return false
        if (ct == "image/svg+xml") return false // 스크립트 실행 가능 → 강제 다운로드
        return ct.startsWith("image/") ||
            ct == "text/plain" ||
            ct == "application/json" ||
            ct == "application/pdf"
    }

    /**
     * 요청 disposition + contentType 으로 실제 응답 disposition 결정.
     * inline 요청이라도 뷰 불가/위험 타입이면 attachment 로 강등.
     */
    fun effectiveDisposition(requested: String?, contentType: String?): String =
        if (requested == "attachment") "attachment"
        else if (isInlineViewable(contentType)) "inline" else "attachment"

    /**
     * 안정 파일 라우트 href. base 가 빈 문자열이면 상대경로(웹 페이지용),
     * 절대 URL(publicBaseUrl)이면 PDF/MD 등 박제 산출물용.
     */
    fun fileHref(base: String, slug: String, markerId: String, disposition: String): String =
        "$base/p/$slug/files/$markerId?disposition=$disposition"
}

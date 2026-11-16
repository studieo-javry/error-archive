package org.studieojavry.coreapi.agentcapture.application.usecase

/**
 * Capture 반영(Import) 시 본문·스텝의 마크다운 펜스 코드블록(```lang … ```)을 코드 스니펫(@snippet)으로
 * 승격하기 위한 **순수 파서**. 생성/링크(부수효과)는 ImportCaptureUseCase 가 담당하고, 이 클래스는
 * 텍스트 → (치환 템플릿, 승격할 블록 목록) 변환만 한다(테스트 용이).
 *
 * 정책:
 *  - **언어 태그가 있는 블록**(```kotlin 등, text 제외)은 항상 승격한다.
 *  - **무태그 / ```text** 블록은 **내용 줄 수가 [minLines] 이상일 때만** 승격한다(1줄짜리 로그·명령 과승격 방지).
 *  - 닫히지 않은 펜스·들여쓴 펜스는 승격하지 않고 원문 그대로 둔다(보수적).
 *  - info-string 의 첫 토큰 = 언어, 나머지 = 파일경로/클래스(선택 규약). 예: ```kotlin src/Foo.kt
 */
class FencedCodePromoter(private val minLines: Int = 2) {

    data class FencedBlock(val language: String, val code: String, val filePathOrClass: String?)

    /** 라인 토큰: 그대로 출력할 문자열이거나, i 번째 승격 블록(렌더 시 @snippet 으로 치환). */
    internal sealed interface LineToken {
        data class Literal(val text: String) : LineToken
        data class Snippet(val index: Int) : LineToken
    }

    /** scan 결과(불투명). blocks 로 승격 블록을 읽고, render 로 @snippet 치환본을 얻는다. */
    class Scan internal constructor(internal val lines: List<LineToken>, val blocks: List<FencedBlock>)

    /** body 를 스캔해 승격 대상 펜스를 토큰화한다. null/blank 는 빈 결과. */
    fun scan(body: String?): Scan {
        if (body.isNullOrBlank()) return Scan(emptyList(), emptyList())
        val src = body.split("\n")
        val out = ArrayList<LineToken>()
        val blocks = ArrayList<FencedBlock>()

        var i = 0
        while (i < src.size) {
            val line = src[i]
            // 펜스 열림: 들여쓰기 없는 ``` (정보문자열은 열림에만). 닫힘과 구분은 상태로.
            if (line.startsWith("```")) {
                val info = line.substring(3).trim()
                val langTok = info.substringBefore(' ').trim()
                val pathTok = (if (' ' in info) info.substringAfter(' ') else "").trim().ifBlank { null }
                // 닫는 ``` 찾기
                var j = i + 1
                while (j < src.size && src[j].trim() != "```") j++
                if (j >= src.size) {
                    // 닫히지 않음 → 원문 유지(이 줄부터 끝까지 리터럴)
                    for (k in i until src.size) out.add(LineToken.Literal(src[k]))
                    i = src.size
                    continue
                }
                val content = src.subList(i + 1, j).joinToString("\n")
                val lineCount = j - (i + 1)
                val realLang = langTok.isNotBlank() && langTok != "text"
                val promote = realLang || lineCount >= minLines
                if (promote) {
                    val language = if (langTok.isBlank()) "text" else langTok
                    out.add(LineToken.Snippet(blocks.size))
                    blocks.add(FencedBlock(language = language, code = content, filePathOrClass = pathTok))
                } else {
                    // 승격 안 함 → 펜스 원문 그대로(열림·내용·닫힘)
                    for (k in i..j) out.add(LineToken.Literal(src[k]))
                }
                i = j + 1
            } else {
                out.add(LineToken.Literal(line))
                i++
            }
        }
        return Scan(out, blocks)
    }

    /** scan 결과를 blockIndex→markerId 로 렌더. markers.size 는 scan.blocks.size 와 같아야 한다. */
    fun render(scan: Scan, markers: List<String>): String {
        require(markers.size == scan.blocks.size) { "markers size ${markers.size} != blocks ${scan.blocks.size}" }
        return scan.lines.joinToString("\n") { tok ->
            when (tok) {
                is LineToken.Literal -> tok.text
                is LineToken.Snippet -> "@snippet(${markers[tok.index]})"
            }
        }
    }
}

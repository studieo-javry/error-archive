package org.studieojavry.publishapi.publishment.domain

/**
 * publish 시 사용자가 고른 표시 옵션.
 *
 * jsonb 한 컬럼에 통째 저장. 새 옵션 추가 시 본 클래스에 필드만 추가.
 *
 * 제거됨(MVP2 보류): language / theme / maskingRules — 발행물은 단일 웹 아티클(고정 톤)로,
 * 마스킹은 추후 재도입 가능. jsonb 라 legacy row 에 남은 키는 역직렬화 시 무시된다.
 */
data class PublishOptions(
    /** 각 step 사이 소요 시간 표시 여부 ("3 hr 후 다음 step") */
    val showStepDuration: Boolean = true,
    /** 각 step 시각 표기 — EXACT / RELATIVE(Day N) / NONE */
    val timeStyle: TimeStyle = TimeStyle.RELATIVE,
    /** 코드 라인 번호 표시 */
    val codeLineNumbers: Boolean = true,
    /** 작성자 표시명 — null 이면 익명 */
    val authorDisplayName: String? = null,
) {
    enum class TimeStyle { EXACT, RELATIVE, NONE }
    // 첨부 표현은 kind(이미지 인라인 / 그 외 링크)로 자동 — attachmentMode 옵션 폐기(방향 A).
    // include/exclude 는 발행 요청의 excludedAttachmentMarkerIds 로 처리(ContentSnapshotBuilder).

    /**
     * 마스킹은 MVP2 로 보류 — 현재 passthrough(치환 없음).
     * 호출부(ContentSnapshotBuilder)를 건드리지 않으려 시그니처만 유지한다.
     */
    fun applyMasking(text: String?): String? = text

    companion object {
        fun defaults() = PublishOptions()
    }
}

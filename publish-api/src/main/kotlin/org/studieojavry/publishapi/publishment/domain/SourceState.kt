package org.studieojavry.publishapi.publishment.domain

/**
 * 발행물의 원본 case 라이프사이클 상태
 *
 * 발행물 자체는 불변 스냅샷이라 원본이 바뀌어도 보존되지만(정책 A),
 * "원본이 어떤 상태인지" 를 표시해 사용자에게 재발행/삭제 안내를 준다.
 *
 * - `LIVE`           — 원본 존재. (원본 수정으로 stale 인지는 별도 lazy 계산 — 저장 안 함)
 * - `SOURCE_DELETED` — 원본 case 삭제됨. 발행물은 유지하되 재발행 불가
 *
 * NOTE: 현재는 소유자 목록 조회 시 core 에 질의하는 lazy 판정
 * 추후 core→publish `case.deleted` 이벤트 구독(event-driven)으로 실시간 갱신하도록 바꿀 수 있음
 */
enum class SourceState {
    LIVE,
    SOURCE_DELETED,
}

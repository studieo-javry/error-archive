package org.studieojavry.publishapi.publishment.domain

/**
 * 발행물 라이프사이클 상태 — visibility(누가 보나)·sourceState(원본 상태)와 직교.
 *
 *  - LIVE        : 공개 중. `/p/{slug}` 정상 서빙, 목록/discovery 노출(visibility 조건 하)
 *  - UNPUBLISHED : 내려짐(soft). `/p/{slug}` 410 Gone, 목록/discovery 제외. slug·스냅샷·counters 보존 → 재공개 가능
 *
 * 완전 삭제(hard)는 row 자체를 제거하므로 별도 status 값이 아님(404).
 */
enum class PublishmentStatus { LIVE, UNPUBLISHED }

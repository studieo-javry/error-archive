package org.studieojavry.publishapi.publishment.domain

/**
 * publish 공개 범위 정책 (누가 보는가 — status 와 직교)
 *
 *  - PUBLIC    : 검색/discovery(sitemap)에 노출, 누구나 발견 (default)
 *  - UNLISTED  : 검색에 안 잡힘(robots noindex), 링크 아는 사람만
 *
 * PRIVATE 는 제거됨 — "웹에 발행하되 나만 봄" 은 웹 발행 개념과 상충. 나만 보려면 발행하지 않거나
 * export(PDF/MD)로 내려받으면 됨. 내려두기(archived)는 status=UNPUBLISHED 로 표현.
 */
enum class Visibility { PUBLIC, UNLISTED }

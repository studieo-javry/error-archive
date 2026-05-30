package org.studieojavry.coreapi.errorcase.case.domain.model.vo

/**
 * 에러케이스의 가시성(누가 읽을 수 있나).
 *
 *  - `PUBLIC`    : 로그인된 모든 사용자 read·댓글 가능. 수정은 여전히 owner 만.
 *  - `PRIVATE`   : owner 만 read·수정 가능.
 *  - `WORKSPACE` : 해당 워크스페이스 멤버(READ+)만 read. 수정은 owner+WRITE+ 멤버.
 *
 * 신규 케이스 기본값은 PUBLIC(공유 자산화 정체성).
 *
 * **워크스페이스→PUBLIC 승격은 워크스페이스 ADMIN 만** — 일반 멤버가 워크스페이스 자산을 외부로
 * 흘리는 사고 방지. (참고: `revisitable-decisions.md` §4 의 Solution 매칭과 동일 정책.)
 *
 * 도메인 invariant: `WORKSPACE` 는 `meta.workspaceId != null` 일 때만 유효.
 */
enum class Visibility { PUBLIC, PRIVATE, WORKSPACE }

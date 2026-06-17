package org.studieojavry.coreapi.errorcase.shared.application.port

/**
 * iam-api 의 `GET /internal/workspaces/{id}/member-ids` 호출.
 *
 * RESOLVED 알림 fan-out 시 워크스페이스 멤버 userId list 필요.
 * 호출 실패는 *조용히 emptyList 반환* — 알림 누락이 도메인 작업(케이스 status 변경)을 막지 않음.
 */
interface WorkspaceMemberReaderPort {
    fun findMemberIds(workspaceId: Long): List<Long>

    /**
     * userId 가 속한 *모든 워크스페이스의 다른 멤버* userIds (자기 제외, distinct).
     * suggested-followees 추천의 *W* 신호.
     */
    fun findCoMemberIds(userId: Long, size: Int = 200): List<Long>
}

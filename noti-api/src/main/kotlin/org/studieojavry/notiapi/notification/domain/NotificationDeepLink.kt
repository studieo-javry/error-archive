package org.studieojavry.notiapi.notification.domain

/**
 * type 별로 FE 가 이동해야 할 URL 을 만들어준다.
 * FE 는 type 별 switch 를 다시 구현할 필요 없이 응답에 포함된 `deepLink` 만 따라가면 됨.
 *
 * 새 NotificationType 추가 시 여기 case 를 *반드시* 함께 추가 — `when` 이 exhaustive 라
 * 컴파일 단계에서 누락이 잡힌다.
 */
object NotificationDeepLink {
    fun from(type: NotificationType, payload: Map<String, Any?>): String? = when (type) {
        NotificationType.MENTION_IN_COMMENT -> errorCaseCommentLink(payload)
        NotificationType.COMMENT_REPLY -> errorCaseCommentLink(payload)
        NotificationType.COMMENT_ON_ERROR_CASE -> errorCaseCommentLink(payload)
        NotificationType.NEW_FOLLOWER -> {
            val fid = payload["followerId"]?.toString()
            if (!fid.isNullOrBlank()) "/users/$fid" else null
        }
        NotificationType.WORKSPACE_INVITATION -> {
            val ws = payload["workspaceId"]?.toString()
            val inv = payload["invitationId"]?.toString()
            when {
                !ws.isNullOrBlank() && !inv.isNullOrBlank() -> "/workspaces/$ws?invitation=$inv"
                !ws.isNullOrBlank() -> "/workspaces/$ws"
                else -> null
            }
        }
    }

    private fun errorCaseCommentLink(payload: Map<String, Any?>): String? {
        val ec = payload["errorCaseId"]?.toString()
        val cm = payload["commentId"]?.toString()
        return if (!ec.isNullOrBlank() && !cm.isNullOrBlank()) "/error-cases/$ec#comment-$cm" else null
    }
}

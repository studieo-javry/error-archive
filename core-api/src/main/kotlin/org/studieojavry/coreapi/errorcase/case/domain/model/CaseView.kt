package org.studieojavry.coreapi.errorcase.case.domain.model

import java.time.LocalDateTime

/**
 * 특정 사용자가 특정 케이스를 *마지막으로 본 시각*. 홈 대시보드의 "안 본 활동(unread)" 산정용.
 *
 * PK = (userId, errorCaseId). 같은 case 를 여러 번 봐도 row 는 하나 — lastViewedAt 만 갱신.
 *
 * **MVP 정책**: case owner 가 자기 case 의 상세를 GET 할 때만 view 등록 (다른 사용자 view 추적 X).
 */
data class CaseView(
    val userId: Long,
    val errorCaseId: Long,
    val lastViewedAt: LocalDateTime,
) {
    companion object {
        fun touch(userId: Long, errorCaseId: Long) =
            CaseView(userId = userId, errorCaseId = errorCaseId, lastViewedAt = LocalDateTime.now())
    }
}

package org.studieojavry.insightapi.activity.application.port

import org.studieojavry.insightapi.activity.domain.ActivityEvent

interface ActivityEventRepositoryPort {
    /** 멱등 INSERT. 같은 idempotencyKey 가 이미 있으면 null 반환 (= 중복). */
    fun insertIfAbsent(event: ActivityEvent): Long?
}

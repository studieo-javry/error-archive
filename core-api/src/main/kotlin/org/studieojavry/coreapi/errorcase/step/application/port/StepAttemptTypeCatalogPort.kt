package org.studieojavry.coreapi.errorcase.step.application.port

import org.springframework.stereotype.Repository

/**
 * Step 시도 분류(`attemptType`) 카탈로그. system 값은 코드(`AttemptType.SYSTEM`) 상수이고,
 * 사용자 커스텀 값만 본 포트가 관리한다. *사용자별* 카탈로그 — 다른 사용자의 커스텀은 보이지 않는다.
 */
@Repository
interface StepAttemptTypeCatalogPort {
    /** 사용자의 모든 커스텀(원본 name, createdAt ASC 순). */
    fun findAllByUserId(userId: Long): List<String>

    /** 멱등 add — 이미 같은 normalized 가 있으면 false, 새로 추가하면 true. */
    fun add(userId: Long, name: String, normalized: String): Boolean

    /** 단건 remove — 삭제된 row 수(0/1). */
    fun remove(userId: Long, normalized: String): Int

    /** 본인 카탈로그에 normalized 가 있는지. */
    fun exists(userId: Long, normalized: String): Boolean
}

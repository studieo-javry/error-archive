package org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * 케이스 생성(POST /error-cases) 요청 멱등성 기록.
 *
 * `(idempotency_key, user_id)` unique — 같은 사용자의 같은 키 재요청은 최초 case id 로 재생.
 * `created_at` 은 정리(cleanup) 스케줄러가 오래된 row 를 지우는 기준.
 */
@Entity
@Table(
    name = "case_idempotency",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_case_idem_key_user", columnNames = ["idempotency_key", "user_id"]),
    ],
    indexes = [
        Index(name = "ix_case_idem_created", columnList = "created_at"),
    ],
)
class CaseIdempotencyEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "idempotency_key", nullable = false, length = 200)
    var idempotencyKey: String,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "request_hash", nullable = false, length = 64)
    var requestHash: String,

    @Column(name = "error_case_id", nullable = false)
    var errorCaseId: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
)

package org.studieojavry.coreapi.errorcase.step.infrastructure.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "step_attempt_type_custom",
    uniqueConstraints = [UniqueConstraint(name = "uq_step_attempt_type_custom", columnNames = ["user_id", "normalized"])],
    indexes = [Index(name = "ix_step_attempt_type_custom_user", columnList = "user_id")],
)
class StepAttemptTypeCustomEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    /** 표시용 원본(예: "DB-Migration", "infra tweak"). */
    @Column(name = "name", nullable = false, length = 64)
    var name: String,

    /** 동등성 비교용 키 (소문자) — UNIQUE 제약에 사용. */
    @Column(name = "normalized", nullable = false, length = 64)
    var normalized: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)

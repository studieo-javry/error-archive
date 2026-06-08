package org.studieojavry.coreapi.shared.scheduling

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * ShedLock 의 분산 락 테이블(`shedlock`)을 JPA 엔티티로 정의한다.
 *
 * ShedLock 의 JDBC 프로바이더는 이 테이블을 **직접 생성하지 않으므로**, 별도 마이그레이션 도구가
 * 없는 현재 구성(ddl-auto=update)에서는 엔티티로 선언해 스키마가 자동 생성되게 한다.
 * 컬럼명/타입은 `JdbcTemplateLockProvider` 의 기본 스키마(name, lock_until, locked_at, locked_by)와 일치해야 한다.
 *
 * 이 엔티티는 애플리케이션 도메인이 아니라 **인프라(락 테이블) 매핑** 목적이다.
 */
@Entity
@Table(name = "shedlock")
class ShedLockEntity(
    @Id
    @Column(name = "name", length = 64)
    var name: String = "",

    @Column(name = "lock_until", nullable = false)
    var lockUntil: Instant = Instant.EPOCH,

    @Column(name = "locked_at", nullable = false)
    var lockedAt: Instant = Instant.EPOCH,

    @Column(name = "locked_by", length = 255, nullable = false)
    var lockedBy: String = "",
)

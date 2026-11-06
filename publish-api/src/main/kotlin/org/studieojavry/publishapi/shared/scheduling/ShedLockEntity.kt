package org.studieojavry.publishapi.shared.scheduling

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "shedlock")
class ShedLockEntity(
    @Id
    @Column(name = "name", length = 64)
    var name: String = "",

    @Column(name = "lock_until", nullable = false)
    var lockUntil: LocalDateTime = EPOCH_UTC,

    @Column(name = "locked_at", nullable = false)
    var lockedAt: LocalDateTime = EPOCH_UTC,

    @Column(name = "locked_by", length = 255, nullable = false)
    var lockedBy: String = "",
)

// 시각은 UTC 벽시계(TIMESTAMP). usingDbTime() 이 timezone('utc', CURRENT_TIMESTAMP) 를 넣으므로 tz 없는 타입이어야 한다.
private val EPOCH_UTC: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)

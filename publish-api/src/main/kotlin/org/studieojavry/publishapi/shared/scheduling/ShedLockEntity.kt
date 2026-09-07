package org.studieojavry.publishapi.shared.scheduling

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

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

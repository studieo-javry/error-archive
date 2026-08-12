package org.studieojavry.notiapi.notification.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DeviceTokenJpaRepository : JpaRepository<DeviceTokenEntity, Long> {
    fun findAllByUserId(userId: Long): List<DeviceTokenEntity>
    fun findByUserIdAndToken(userId: Long, token: String): DeviceTokenEntity?

    @Modifying(clearAutomatically = true)
    @Query("delete from DeviceTokenEntity d where d.userId = :userId and d.token = :token")
    fun deleteByUserIdAndToken(@Param("userId") userId: Long, @Param("token") token: String): Int
}

package org.studieojavry.notiapi.notification.infrastructure

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface NotificationJpaRepository : JpaRepository<NotificationEntity, Long> {

    fun findAllByRecipientUserIdOrderByCreatedAtDescIdDesc(
        recipientUserId: Long, pageable: Pageable,
    ): List<NotificationEntity>

    fun findAllByRecipientUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(
        recipientUserId: Long, pageable: Pageable,
    ): List<NotificationEntity>

    fun countByRecipientUserIdAndReadAtIsNull(recipientUserId: Long): Long

    @Modifying(clearAutomatically = true)
    @Query(
        """
        update NotificationEntity n
           set n.readAt = :now
         where n.id = :id
           and n.recipientUserId = :userId
           and n.readAt is null
        """
    )
    fun markReadIfOwner(
        @Param("id") id: Long,
        @Param("userId") userId: Long,
        @Param("now") now: Instant,
    ): Int

    @Modifying(clearAutomatically = true)
    @Query(
        """
        update NotificationEntity n
           set n.readAt = :now
         where n.recipientUserId = :userId
           and n.readAt is null
        """
    )
    fun markAllReadByUser(@Param("userId") userId: Long, @Param("now") now: Instant): Int
}

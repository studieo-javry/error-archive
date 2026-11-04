package org.studieojavry.coreapi.errorcase.sentry.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.studieojavry.coreapi.errorcase.sentry.infrastructure.jpa.entity.SentryIssueCaseEntity
import org.studieojavry.coreapi.errorcase.sentry.infrastructure.jpa.entity.SentryProjectLinkEntity

interface SentryProjectLinkJpaRepository : JpaRepository<SentryProjectLinkEntity, Long> {
    fun findBySentryProject(sentryProject: String): SentryProjectLinkEntity?
}

interface SentryIssueCaseJpaRepository : JpaRepository<SentryIssueCaseEntity, String>

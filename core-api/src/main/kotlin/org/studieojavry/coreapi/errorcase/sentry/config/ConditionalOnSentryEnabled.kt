package org.studieojavry.coreapi.errorcase.sentry.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/** `core.sentry.enabled=true` 일 때만 빈 등록 — 끄면 시크릿 없이 부팅하고 Sentry 웹훅도 404. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(prefix = "core.sentry", name = ["enabled"], havingValue = "true")
annotation class ConditionalOnSentryEnabled

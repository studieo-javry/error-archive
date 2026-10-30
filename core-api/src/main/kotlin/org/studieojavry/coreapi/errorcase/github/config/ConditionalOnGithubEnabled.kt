package org.studieojavry.coreapi.errorcase.github.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/** `core.github.enabled=true` 일 때만 빈 등록 — 끄면 시크릿 없이 부팅하고 연동 API 도 404. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(prefix = "core.github", name = ["enabled"], havingValue = "true")
annotation class ConditionalOnGithubEnabled

package org.studieojavry.coreapi.errorcase.sentry.application.usecase

/** Sentry 웹훅 서명 불일치 — 호출부에서 401 로 변환. */
class SentryWebhookSignatureException(message: String) : RuntimeException(message)

package org.studieojavry.coreapi.errorcase.github.application.usecase

/** 설치 state 가 위조·만료·다른 사용자 것. */
class GithubInstallStateInvalidException(message: String) : RuntimeException(message)

/** 콜백의 installation 을 이 GitHub 사용자가 실제로 접근할 수 없음(남의 설치 id 끼워넣기). */
class GithubInstallationNotOwnedException(message: String) : RuntimeException(message)

/** 이미 다른 주체(사용자/워크스페이스)에 연결된 설치. */
class GithubInstallationAlreadyBoundException(message: String) : RuntimeException(message)

/** 웹훅 서명 불일치/누락. */
class GithubWebhookSignatureException(message: String) : RuntimeException(message)

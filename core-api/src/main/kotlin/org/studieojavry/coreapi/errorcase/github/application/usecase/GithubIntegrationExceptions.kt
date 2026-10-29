package org.studieojavry.coreapi.errorcase.github.application.usecase

/** 설치 state 가 위조·만료·다른 사용자 것. */
class GithubInstallStateInvalidException(message: String) : RuntimeException(message)

/** 콜백의 installation 을 이 GitHub 사용자가 실제로 접근할 수 없음(남의 설치 id 끼워넣기). */
class GithubInstallationNotOwnedException(message: String) : RuntimeException(message)

/** 이미 다른 주체(사용자/워크스페이스)에 연결된 설치. */
class GithubInstallationAlreadyBoundException(message: String) : RuntimeException(message)

/** 웹훅 서명 불일치/누락. */
class GithubWebhookSignatureException(message: String) : RuntimeException(message)

/** 지원하지 않는 GitHub 주소 (github.com 의 issues/pull 주소만 허용). */
class GithubUrlInvalidException(message: String) : RuntimeException(message)

/** 주소의 레포가 활성 GitHub 연결(설치)에 포함되어 있지 않음. */
class GithubRepositoryNotConnectedException(message: String) : RuntimeException(message)

/** 레포의 연결 범위(개인/워크스페이스)가 Case 범위와 다름 — 다른 곳의 비공개 정보가 섞이지 않게 거부. */
class GithubScopeMismatchException(message: String) : RuntimeException(message)

/** GitHub 에 그 Issue/PR 이 없거나 App 이 볼 수 없음. */
class GithubItemNotFoundException(message: String) : RuntimeException(message)

/** 이 Case 의 연결이 아님 / 없는 연결. */
class CaseGithubLinkNotFoundException(message: String) : RuntimeException(message)

/** Step 꼬리표 요청에 이 Case 의 활성 근거가 아닌 연결 id 가 있음. */
class GithubStepLinkInvalidException(message: String) : RuntimeException(message)

/** 타임라인 커서 형식이 틀림. */
class GithubEventCursorInvalidException(message: String) : RuntimeException(message)

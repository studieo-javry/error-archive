package org.studieojavry.coreapi.errorcase.github.application.usecase

import java.time.Duration

/** 설치 state 가 위조·만료·다른 사용자 것. */
class GithubInstallStateInvalidException(message: String) : RuntimeException(message)

/** 콜백의 installation 을 이 GitHub 사용자가 실제로 접근할 수 없음(남의 설치 id 끼워넣기). */
class GithubInstallationNotOwnedException(message: String) : RuntimeException(message)

/** 이미 다른 주체(사용자/워크스페이스)에 연결된 설치. */
class GithubInstallationAlreadyBoundException(message: String) : RuntimeException(message)

/** 후보 토큰(candidateToken)이 위조·만료·다른 사용자 것. (connection-candidates → POST /connections 사이 서명 토큰) */
class GithubConnectionCandidateInvalidException(message: String) : RuntimeException(message)

/** 선택한 레포가 설치에 grant 되지 않았거나(= 후보 밖) 비어 유효하지 않음. */
class GithubRepositorySelectionInvalidException(message: String) : RuntimeException(message)

/** 이 사용자가 접근할 수 있는 ACTIVE 연결이 아님 / 없는 연결 id. */
class GithubConnectionNotFoundException(message: String) : RuntimeException(message)

/** 클라이언트가 보낸 expectedVersion 이 현재 연결 버전과 달라 동시 편집 충돌(다른 관리자가 먼저 바꿈). */
class GithubConnectionVersionConflictException(message: String) : RuntimeException(message)

/**
 * 토큰 의존 검증(워크스페이스에 새 레포 공유 시 repo-admin 재확인)을 할 수 없음 — 저장된 GitHub 사용자 토큰이
 * 없거나 만료·취소됨. FE 는 이 code 를 보고 "GitHub 다시 연결" 로 유도한다(단순 거부가 아님).
 */
class GithubReauthorizationRequiredException(message: String) : RuntimeException(message)

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

/** Issue 로 Case 만들 때 고를 수 없는 공개 범위(예: 비공개 저장소 Issue → PUBLIC). */
class GithubImportVisibilityException(message: String) : RuntimeException(message)

/** Issue 로 Case 만들 때 초안에 없는 값을 골랐음(예: 우회 방법 섹션 없는 Issue 에 우회 Step 요청, 범위 밖 심각도). */
class GithubImportInvalidRequestException(message: String) : RuntimeException(message)

/**
 * GitHub 호출 한도에 걸려 지금은 처리할 수 없음 — 장애(503)가 아니라 [retryAfter] 뒤에 다시 하면 되는 상태(429 + Retry-After).
 * 컨트롤러가 [org.studieojavry.coreapi.errorcase.github.application.port.GithubApiException.retryAfter] 를 보고 바꿔 던진다.
 */
class GithubRateLimitedException(val retryAfter: Duration, cause: Throwable? = null) :
    RuntimeException("GitHub 호출 한도 초과 — ${retryAfter.seconds}초 뒤 다시 시도", cause)

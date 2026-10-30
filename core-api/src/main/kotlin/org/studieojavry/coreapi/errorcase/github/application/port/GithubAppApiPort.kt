package org.studieojavry.coreapi.errorcase.github.application.port

/**
 * GitHub API 호출 경계. 구현은 App JWT / installation 토큰 / 사용자 토큰을 상황별로 고른다.
 * 실패는 [GithubApiException] — 사용자 오류(잘못된 code 등)는 [GithubApiException.clientError]=true.
 */
interface GithubAppApiPort {

    /** 설치 콜백의 `code` → 사용자 토큰. 설치 소유 검증([listUserInstallationIds])에만 쓰고 버린다. */
    fun exchangeUserCode(code: String): String

    /** 이 사용자 토큰으로 접근 가능한 App 설치 id 목록 (`GET /user/installations`). */
    fun listUserInstallationIds(userToken: String): Set<Long>

    /** 설치 메타 (`GET /app/installations/{id}`, App JWT). */
    fun getInstallation(installationId: Long): InstallationInfo

    /** 설치 대상 레포 전체 (`GET /installation/repositories`, installation 토큰). */
    fun listInstallationRepositories(installationId: Long): List<RepositoryInfo>

    data class InstallationInfo(val id: Long, val accountLogin: String, val accountType: String)
    data class RepositoryInfo(val id: Long, val fullName: String, val isPrivate: Boolean)
}

class GithubApiException(message: String, val clientError: Boolean = false, cause: Throwable? = null) :
    RuntimeException(message, cause)

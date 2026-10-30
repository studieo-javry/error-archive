package org.studieojavry.coreapi.errorcase.github.domain.model.vo

/** Case 에 붙는 GitHub 대상 종류. */
enum class GithubRefKind { ISSUE, PULL_REQUEST }

/**
 * 사용자가 붙여넣은 GitHub Issue/PR 주소. `github.com` 만 허용.
 *  - `https://github.com/{owner}/{repo}/issues/{n}`
 *  - `https://github.com/{owner}/{repo}/pull/{n}` (뒤의 `/files`, `#discussion...`, `?query` 허용)
 *
 * [kind] 는 주소가 말하는 종류일 뿐 — GitHub 는 `/issues/{PR번호}` 도 PR 로 보내므로 최종 종류는 API 응답으로 정한다.
 */
data class GithubUrl(val owner: String, val repo: String, val kind: GithubRefKind, val number: Int) {
    val fullName: String get() = "$owner/$repo"

    companion object {
        private val PATTERN = Regex(
            """^https?://(?:www\.)?github\.com/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100})/(issues|pull)/(\d{1,9})(?:[/?#].*)?$""",
            RegexOption.IGNORE_CASE,
        )

        fun parse(raw: String?): GithubUrl? {
            val m = PATTERN.matchEntire(raw?.trim().orEmpty()) ?: return null
            val (owner, repo, type, number) = m.destructured
            val n = number.toInt()
            if (n <= 0) return null
            val kind = if (type.equals("pull", ignoreCase = true)) GithubRefKind.PULL_REQUEST else GithubRefKind.ISSUE
            return GithubUrl(owner, repo.removeSuffix(".git"), kind, n)
        }
    }
}

/** GitHub Issue/PR 식별자. 레포 이름은 바뀌므로 불변 repo id + 번호로 식별. */
data class GithubItemRef(val repoId: Long, val number: Int)

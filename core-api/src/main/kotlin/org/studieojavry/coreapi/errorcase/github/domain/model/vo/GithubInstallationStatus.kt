package org.studieojavry.coreapi.errorcase.github.domain.model.vo

/** 설치 상태. DELETED 는 행을 지우지 않고 표시만 — 이미 쌓인 근거 스냅샷의 출처 추적용. */
enum class GithubInstallationStatus { ACTIVE, SUSPENDED, DELETED }

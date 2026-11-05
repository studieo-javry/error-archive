package org.studieojavry.coreapi.errorcase.github.domain.model.vo

/** 연결 상태. DISCONNECTED 는 행을 지우지 않고 표시만 — 재연결 시 되살리고, 근거 출처 추적을 보존. */
enum class GithubConnectionStatus { ACTIVE, DISCONNECTED }

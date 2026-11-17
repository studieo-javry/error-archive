package org.studieojavry.iamapi.agentconnection.application.port

/** 로컬 커넥터 최신 배포 버전 공급자(npm 레지스트리 등). 조회 불가 시 null. */
interface ConnectorVersionSource {
    /** dist-tags.latest 등 최신 버전 문자열. 토큰 없음·네트워크·파싱 실패면 null. */
    fun fetchLatest(): String?
}

package org.studieojavry.coreapi.errorcase.github.domain.model.vo

/**
 * 연결별 레포 "사용 선택" 의 상태.
 *  - [ACTIVE]      : 정상 사용 중.
 *  - [ACCESS_LOST] : GitHub 접근(grant/레포)이 사라진 선택. 이력 보존용이며 실효 사용하지 않는다.
 *                    재허용되어도 자동으로 ACTIVE 로 돌아가지 않고, 사용자가 다시 명시 선택해야 한다.
 */
enum class GithubConnectionRepositoryStatus {
    ACTIVE,
    ACCESS_LOST,
}

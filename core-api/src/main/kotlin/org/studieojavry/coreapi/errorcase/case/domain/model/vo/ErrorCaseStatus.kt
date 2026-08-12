package org.studieojavry.coreapi.errorcase.case.domain.model.vo

enum class ErrorCaseStatus {
    // TODO: DRAFT 임시 저장 모드에 대한 구현은 어떻게 할 것인지?
    DRAFT,
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED
    ;
}

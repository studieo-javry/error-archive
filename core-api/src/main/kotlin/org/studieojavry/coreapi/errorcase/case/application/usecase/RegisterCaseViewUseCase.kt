package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.CaseViewRepositoryPort
import org.studieojavry.coreapi.errorcase.case.domain.model.CaseView

/**
 * 사용자가 case 를 *방금 봤다* 는 사실을 기록. `lastViewedAt = now`. PK (userId, caseId) upsert.
 *
 * **MVP 정책**: case owner 가 자기 case 상세를 GET 할 때만 호출 — 즉, *내 case 의 "안 본 활동"*
 * 계산용으로만 쓰임. 다른 사용자의 view 는 추적하지 않는다.
 */
@Service
class RegisterCaseViewUseCase(
    private val caseViewRepository: CaseViewRepositoryPort,
) {
    @Transactional
    fun invoke(userId: Long, errorCaseId: Long) {
        caseViewRepository.upsert(CaseView.touch(userId = userId, errorCaseId = errorCaseId))
    }
}

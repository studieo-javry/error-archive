package org.studieojavry.coreapi.errorcase.case.infrastructure.llm

import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.DraftExtractionPort

/**
 * slice 1 기본 어댑터 — LLM 미연동 상태.
 *
 * 항상 null 을 반환해 UseCase 가 **정규식 폴백만** 쓰도록 한다. (LLM 키/프로바이더 없이도 기능이 돈다)
 * slice 2 에서 실제 LLM 어댑터를 `@Primary` 로 추가하면 이 어댑터는 자동으로 뒤로 밀린다.
 */
@Component
class NoOpDraftExtractionAdapter : DraftExtractionPort {
    override fun extract(input: DraftExtractionPort.Input): DraftExtractionPort.Output? = null
}

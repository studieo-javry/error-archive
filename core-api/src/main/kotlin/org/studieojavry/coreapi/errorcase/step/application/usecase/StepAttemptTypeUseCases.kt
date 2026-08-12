package org.studieojavry.coreapi.errorcase.step.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.step.application.port.StepAttemptTypeCatalogPort
import org.studieojavry.coreapi.errorcase.step.domain.model.vo.AttemptType

/**
 * Step 시도 분류 카탈로그 — system + 본인 custom 평탄 list 로 반환.
 * system 이 먼저 (선언 순서), 그 다음 본인 custom (createdAt ASC).
 */
@Service
class ListStepAttemptTypesUseCase(
    private val catalog: StepAttemptTypeCatalogPort,
) {
    @Transactional(readOnly = true)
    fun invoke(userId: Long): List<Item> {
        val system = AttemptType.SYSTEM.map { Item(name = it, isSystem = true) }
        val custom = catalog.findAllByUserId(userId).map { Item(name = it, isSystem = false) }
        return system + custom
    }

    data class Item(val name: String, val isSystem: Boolean)
}

/**
 * 명시적 카탈로그 추가. 멱등.
 * system 값이면 *이미 있는 것* 이라 added=false 반환.
 */
@Service
class AddStepAttemptTypeUseCase(
    private val catalog: StepAttemptTypeCatalogPort,
) {
    @Transactional
    fun invoke(userId: Long, rawName: String): Result {
        val name = AttemptType.normalize(rawName)
            ?: throw IllegalArgumentException("attemptType must not be blank")
        val key = AttemptType.normalizedKey(name)

        // system 값과 충돌 시 추가 안 함 (이미 모두에게 제공).
        if (AttemptType.SYSTEM.any { AttemptType.normalizedKey(it) == key }) {
            return Result(name = name, added = false, alreadySystem = true)
        }
        val added = catalog.add(userId, name, key)
        return Result(name = name, added = added, alreadySystem = false)
    }

    data class Result(val name: String, val added: Boolean, val alreadySystem: Boolean)
}

/**
 * 본인 카탈로그 단건 제거. system 은 거부(400).
 * 이미 사용 중인 step 의 attemptType String 값은 *유지* — 느슨 결합.
 */
@Service
class RemoveStepAttemptTypeUseCase(
    private val catalog: StepAttemptTypeCatalogPort,
) {
    @Transactional
    fun invoke(userId: Long, rawName: String): Result {
        val key = AttemptType.normalizedKey(rawName.trim())
        if (key.isEmpty()) throw IllegalArgumentException("attemptType must not be blank")
        if (AttemptType.SYSTEM.any { AttemptType.normalizedKey(it) == key }) {
            throw IllegalArgumentException("cannot remove system attemptType: $rawName")
        }
        val removed = catalog.remove(userId, key)
        return Result(name = rawName.trim(), removed = removed > 0)
    }

    data class Result(val name: String, val removed: Boolean)
}

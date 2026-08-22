package org.studieojavry.insightapi.activity.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.studieojavry.insightapi.activity.domain.ActivityType

/**
 * `insight.activity.weights.{TYPE}` — 활동별 가중치. yml 에서 주입.
 * publish 측에서도 같은 가중치 표를 알지만, *서버는 yml 값을 신뢰* — 발사된 score 와 다른 경우
 * 라이브러리 버전 불일치 가능성 → 서버 측 정책 적용.
 *
 * 값 출처(2계층):
 *  - base application.yml: 중립 기본값(전부 1 = 균등 가중). 공개 저장소에 이 값만 노출.
 *  - prod: gitignore 된 config/weights-prod.yml 을 spring.config.import 로 덮어씀(실 튜닝값).
 *
 * fail-loud: 모든 [ActivityType] 에 가중치가 present 여야 부팅. 하나라도 누락되면 즉시 실패 —
 * (신규 타입 추가 후 yml 미갱신 / prod 가중치 파일 미마운트 등을) 조용한 오집계 대신 부팅 시점에 잡는다.
 */
@ConfigurationProperties(prefix = "insight.activity")
data class ActivityWeightProperties(
    val weights: Map<String, Int> = emptyMap(),
) {
    init {
        val missing = ActivityType.entries.filter { it.name !in weights }
        require(missing.isEmpty()) {
            "insight.activity.weights 에 누락된 ActivityType: ${missing.joinToString { it.name }}. " +
                "base application.yml(중립 기본값) 또는 prod weights-prod.yml 마운트를 확인하세요."
        }
    }

    fun weightOf(type: ActivityType): Int =
        weights[type.name] ?: error("weight 미정의: ${type.name} (init 검증을 통과했다면 도달 불가)")
}

package org.studieojavry.insightapi.activity.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.studieojavry.insightapi.activity.domain.ActivityType

/**
 * `insight.activity.weights.{TYPE}` — 활동별 가중치. yml 에서 주입.
 * publish 측에서도 같은 가중치 표를 알지만, *서버는 yml 값을 신뢰* — 발사된 score 와 다른 경우
 * 라이브러리 버전 불일치 가능성 → 서버 측 정책 적용.
 */
@ConfigurationProperties(prefix = "insight.activity")
data class ActivityWeightProperties(
    val weights: Map<String, Int> = emptyMap(),
) {
    fun weightOf(type: ActivityType): Int = weights[type.name] ?: 1
}

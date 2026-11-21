package org.studieojavry.insightapi.activity.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 제품 참여(engagement) 지표 배치의 운영 파라미터.
 *
 * Prometheus 는 "순간 흐름"에 맞는 실시간 계측용이라, 사용자별·시간기반 코호트 지표(가입→첫 케이스,
 * 연속 작성 간격 등)는 라벨에 userId 를 넣으면 **카디널리티 폭발**한다. 그래서 이 지표들은 배치가
 * 원장(`insight_activity_event`)을 집계해 **소수의 저카디널리티 gauge**(분포는 quantile 태그)로만 노출한다.
 *
 * @param enabled             false 면 집계 배치·gauge 등록을 모두 끈다.
 * @param cohortLookbackDays  활성화율/첫-케이스-지연을 측정할 "최근 가입 코호트" 범위(일).
 * @param activityWindowDays  소스믹스·공개율·생성/해결 수를 집계할 활동 창(일).
 * @param signupFetchLimit    iam 가입 코호트 조회 상한(안전 캡).
 */
@ConfigurationProperties(prefix = "insight.product-metrics")
data class ProductMetricsProperties(
    val enabled: Boolean = true,
    val cohortLookbackDays: Long = 30,
    val activityWindowDays: Long = 28,
    val signupFetchLimit: Int = 5000,
)

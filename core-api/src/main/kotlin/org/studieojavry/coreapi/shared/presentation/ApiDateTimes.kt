package org.studieojavry.coreapi.shared.presentation

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 응답용 — 서버 벽시계(LocalDateTime)에 **JVM 기본 시간대의 오프셋**을 붙인다(stg/prod 는 Dockerfile `TZ=Asia/Seoul` → `+09:00`).
 *
 * `timestamp without time zone` 컬럼은 Postgres(UTC) 에 UTC 벽시계로 저장되고, Hibernate 가 읽을 때 JVM 시간대 벽시계로 돌려준다.
 * `LocalDateTime.now()` 도 JVM 시간대다. 그래서 이 계층에 오는 LocalDateTime 은 모두 JVM 시간대 값이고, 여기서 붙이는 오프셋이 맞다.
 * 오프셋이 없으면 브라우저가 시점을 알 수 없다(예: GitHub 이벤트 `…Z` 와 섞어 정렬하면 9시간 틀어짐).
 * 내부 API(publish-api 가 LocalDateTime 으로 읽음)·Kafka 이벤트에는 쓰지 않는다.
 */
fun LocalDateTime.toApiOffset(): OffsetDateTime = atZone(ZoneId.systemDefault()).toOffsetDateTime()

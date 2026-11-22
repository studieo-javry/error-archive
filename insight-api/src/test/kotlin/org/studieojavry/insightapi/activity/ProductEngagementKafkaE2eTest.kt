package org.studieojavry.insightapi.activity

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.studieojavry.insightapi.activity.application.port.UserSignupPort
import org.studieojavry.insightapi.activity.application.port.UserTimezonePort
import org.studieojavry.insightapi.activity.infrastructure.ActivityEventJpaRepository
import org.studieojavry.insightapi.activity.infrastructure.metrics.ProductEngagementMetrics
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.time.ZoneOffset

/**
 * **실 Kafka e2e(docker)** — 실 Kafka 브로커(Testcontainers) + 실 Postgres.
 *
 * core-api 가 내보내는 것과 **동일 스키마**의 `user-activity.v1` 메시지를 실제 브로커로 발행 →
 * 실 [org.studieojavry.insightapi.activity.infrastructure.kafka.ActivityEventConsumer] 가 소비·역직렬화·
 * ingest → 원장 적재 → [ProductEngagementMetrics] 배치 집계 → gauge 를 확정한다.
 * (Testcontainers = docker. 전송·역직렬화·meta_json 파싱까지 실 경로로 검증.)
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = [
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.kafka.consumer.group-id=insight-api",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
    "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
])
class ProductEngagementKafkaE2eTest {

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @Container @JvmStatic
        val kafka = org.testcontainers.kafka.KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"))

        const val TOPIC = "user-activity.v1"

        @JvmStatic
        @DynamicPropertySource
        fun kafkaProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }
    }

    @Autowired private lateinit var events: ActivityEventJpaRepository
    @Autowired private lateinit var metrics: ProductEngagementMetrics
    @Autowired private lateinit var registry: MeterRegistry
    @MockitoBean private lateinit var userTimezone: UserTimezonePort
    @MockitoBean private lateinit var userSignup: UserSignupPort

    private val now = Instant.now()

    @BeforeEach
    fun setUp() {
        Mockito.`when`(userTimezone.fetch(anyLong())).thenReturn(ZoneOffset.UTC)
        // 가입 코호트 — 두 유저 모두 케이스 있음 → 활성화율 1.0
        Mockito.`when`(userSignup.recentSignups(anyInstant(), anyInt())).thenReturn(
            listOf(
                UserSignupPort.Signup(2001L, now.minus(Duration.ofHours(3))),
                UserSignupPort.Signup(2002L, now.minus(Duration.ofHours(27))),
            )
        )
    }

    @Test
    fun `실 Kafka 로 발행한 활동이 소비·적재되고 배치가 지표를 집계한다`() {
        produce(2001L, "e2e:case:2001", now.minus(Duration.ofHours(2)), visibilityPublic = true)
        produce(2002L, "e2e:case:2002", now.minus(Duration.ofHours(26)), visibilityPublic = false)

        // consumer 가 두 건을 원장에 적재할 때까지 대기(실 Kafka 전송·소비)
        awaitLedgerCount(2, timeout = Duration.ofSeconds(40))

        metrics.doRecompute()

        // 28d 활성 작성자 2명(2001, 2002)
        assertEquals(2.0, gauge("ea.active.creators", "window", "28d"))
        // 최근 창 생성 수 2, 공개 1건 → 공개율 0.5 (meta_json->>'visibility' 가 실 Kafka 경로로 파싱됨)
        assertEquals(2.0, gauge("ea.cases.created.window"))
        assertEquals(0.5, gauge("ea.cases.public.ratio"), 1e-6)
        // 소스믹스: 둘 다 사람(USER)
        assertEquals(2.0, gauge("ea.cases.by.origin", "origin", "USER"))
        // 활성화율: 코호트 2명 모두 첫 케이스 보유 → 1.0
        assertEquals(1.0, gauge("ea.activation.rate"), 1e-6)
    }

    private fun produce(userId: Long, key: String, occurredAt: Instant, visibilityPublic: Boolean) {
        val meta = if (visibilityPublic) ""","meta":{"visibility":"PUBLIC"}""" else ""
        val json = """{"userId":$userId,"type":"CASE_CREATED","occurredAt":"$occurredAt","score":0,"idempotencyKey":"$key","origin":"USER"$meta}"""
        producer().use { p ->
            p.send(ProducerRecord(TOPIC, userId.toString(), json)).get()
            p.flush()
        }
    }

    private fun producer(): KafkaProducer<String, String> {
        val props = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        }
        return KafkaProducer(props)
    }

    private fun awaitLedgerCount(expected: Long, timeout: Duration) {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            if (events.count() >= expected) return
            Thread.sleep(500)
        }
        assertTrue(events.count() >= expected, "원장에 $expected 건 적재 대기 타임아웃 (현재 ${events.count()})")
    }

    private fun gauge(name: String, vararg tags: String): Double =
        registry.get(name).tags(*tags).gauge().value()

    private fun anyInstant(): Instant = Mockito.any(Instant::class.java) ?: Instant.EPOCH
}

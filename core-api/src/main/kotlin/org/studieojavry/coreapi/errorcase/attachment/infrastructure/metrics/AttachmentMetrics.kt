package org.studieojavry.coreapi.errorcase.attachment.infrastructure.metrics

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.config.AttachmentStorageProperties
import java.time.Instant

/**
 * 첨부 orphan 운영 가시성 — Micrometer Gauge 로 노출. Prometheus / Actuator 통해 수집.
 *
 *  - `attachment.orphan.unlinked_backlog` : 미연결(errorCaseId IS NULL) + TTL 경과(= GC 대상) row 수.
 *
 * 정상 정상상태 ≈ 0 (10분 주기 GC 가 비운다). 지속적으로 상승 = GC 또는 스토리지 delete 실패 신호.
 *
 * **Alert 가이드**:
 *  - `attachment.orphan.unlinked_backlog` 가 수 시간 동안 상승 추세 → GC / 스토리지 delete 실패 의심
 *    (로그 `[attachment] ... failed` / `[attachment-gc] purge failed` 확인).
 *
 * **한계(의도된 트레이드오프)**: 이 게이지는 **DB row 기준**이라, 프로세스가 put 과 commit 사이에서
 * 죽거나(비정상 종료) 롤백 정리 delete 가 실패해 생기는 **row 없는 스토리지 고아**는 보지 못한다.
 * 그 회수는 스토리지↔DB 조정 스윕(에스컬레이션 과제, `docs/attachment-lifecycle.md` 참고)으로 다룬다.
 * 현재 정책은 "best-effort 수용 + 관측": 비정상 종료 고아는 드물고 비용 문제(정합성·보안 아님)이므로,
 * 이 백로그 게이지·로그로 추세를 보고 누적이 유의미해질 때 조정 스윕으로 전환한다.
 *
 * Gauge 가 매 scrape 마다 DB count — Prometheus scrape interval (보통 15-60s) 와 맞물려 부담 최소.
 */
@Component
class AttachmentMetrics(
    private val repository: ErrorCaseAttachmentRepositoryPort,
    private val properties: AttachmentStorageProperties,
    private val meterRegistry: MeterRegistry,
) {
    @PostConstruct
    fun registerGauges() {
        meterRegistry.gauge("attachment.orphan.unlinked_backlog", this) {
            it.repository.countUnlinkedOlderThan(Instant.now().minus(it.properties.orphanTtl)).toDouble()
        }
    }
}

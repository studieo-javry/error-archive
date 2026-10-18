package org.studieojavry.publishapi.demo.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.studieojavry.publishapi.demo.config.DemoPublishmentProperties
import org.studieojavry.publishapi.publishment.application.port.CasePublishmentRepositoryPort
import org.studieojavry.publishapi.publishment.application.port.CoreCaseDataPort
import org.studieojavry.publishapi.publishment.application.usecase.CreatePublishmentUseCase
import org.studieojavry.publishapi.publishment.application.usecase.PublishInput
import org.studieojavry.publishapi.publishment.application.usecase.PublishmentAlreadyExistsException
import org.studieojavry.publishapi.publishment.application.usecase.PublishmentNotResolvedException
import org.studieojavry.publishapi.publishment.domain.Visibility

/**
 * 데모 게스트 "내 발행물" 지연 시더.
 *
 * 게스트가 발행물 라이브러리(`GET /api/v1/publishments/me`)를 열면, 그 게스트의 **실제 개인 케이스**
 * (core 가 지연 생성해둔 3건)를 **실제 발행 플로우**(CreatePublishmentUseCase)로 발행한다.
 *   - owner = 그 게스트 → `/me` 에 노출. originalCaseId = 실제 개인 케이스 → SOURCE_DELETED 로 안 뜸.
 *   - 실제 플로우 재사용이라 스냅샷 빌드·스니펫/첨부 처리·슬러그가 운영 발행물과 동일(품질 보장).
 *   - `demo = true` 마커 → 야간 purge 대상(게스트 1회용).
 *
 * 베스트-에포트: 어떤 실패도 `/me` 응답을 막지 않는다(상위 컨트롤러가 runCatching 으로 감쌈).
 * 멱등: 이미 데모 발행물을 보유하면 core 왕복 없이 즉시 skip.
 *
 * ⚠️ 순서 전제: 게스트 개인 케이스는 core 홈 피드(`recent-active-cases`) 진입 시 생성된다. 보통 홈→라이브러리
 *    순서라 케이스가 먼저 존재하지만, 라이브러리를 먼저 열면 그 시점엔 비어 다음 방문에 채워진다.
 */
@Service
class DemoGuestPublishmentSeeder(
    private val coreCaseData: CoreCaseDataPort,
    private val createPublishment: CreatePublishmentUseCase,
    private val repository: CasePublishmentRepositoryPort,
    private val props: DemoPublishmentProperties,
) {
    private val log = KotlinLogging.logger {}

    fun ensureSeededForGuest(guestUserId: Long) {
        if (!props.enabled) return
        // 이미 시드됨 → core 왕복 없이 즉시 종료(게스트 /me 재방문마다 호출되므로 저렴해야 함).
        if (repository.existsDemoByOwner(guestUserId)) return

        val caseIds = coreCaseData.fetchMyPersonalCaseIds()
        if (caseIds.isEmpty()) return

        caseIds.forEach { caseId ->
            // 각 발행은 자체 트랜잭션(CreatePublishmentUseCase @Transactional). 개별 실패는 격리.
            runCatching {
                createPublishment.invoke(
                    requesterUserId = guestUserId,
                    idempotencyKey = "demo-pub:$guestUserId:$caseId",
                    requestHash = DEMO_REQUEST_HASH,
                    input = PublishInput(
                        caseId = caseId,
                        title = null,
                        summary = null,
                        // 게스트 개인 발행물은 전부 UNLISTED — "내 발행물"엔 뜨되(상태 무관 3건 보장),
                        // 공개 Explore(PUBLIC)엔 노출하지 않는다(게스트 개인 케이스가 공개 인덱스를 어지럽히지 않게).
                        visibility = Visibility.UNLISTED,
                        includeStepIds = null,
                        includeSolutionIds = null,
                        demo = true,
                    ),
                )
            }.onFailure { e ->
                // 이미 발행됨(멱등 경쟁) / 미해결 케이스(발행 정책상 skip) 는 정상 — 그 외만 경고.
                if (e !is PublishmentAlreadyExistsException && e !is PublishmentNotResolvedException) {
                    log.warn(e) { "demo guest publishment seed failed (guest=$guestUserId, case=$caseId)" }
                }
            }
        }
    }

    private companion object {
        // 데모 시드는 고정 콘텐츠 → 멱등 재생 비교용 requestHash 는 상수면 충분.
        const val DEMO_REQUEST_HASH = "demo-seed"
    }
}

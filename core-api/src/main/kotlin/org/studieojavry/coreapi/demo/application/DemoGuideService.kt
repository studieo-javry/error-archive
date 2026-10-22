package org.studieojavry.coreapi.demo.application

import org.springframework.stereotype.Service
import org.studieojavry.coreapi.demo.config.DemoProperties
import org.studieojavry.coreapi.demo.infrastructure.DemoIamIdentityClient
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria

/**
 * FE 사용 가이드용 — 가이드가 따라갈 샘플 케이스(오늘의 ID)와 4단계에서 열 공개 아티클 slug.
 *
 * 데모 WS 는 야간 wipe+reseed 라 케이스 ID 가 매일 바뀐다 → 요청 시점에 봇 시드 중
 * [DemoSeeder.GUIDE_CASE_TITLE] 케이스를 찾는다. 아티클은 publish-api 가 고정 slug 로 시드하므로 설정값 그대로.
 */
@Service
class DemoGuideService(
    private val props: DemoProperties,
    private val identityClient: DemoIamIdentityClient,
    private val errorCaseRepository: ErrorCaseRepositoryPort,
) {

    /** 데모 비활성 / identity 조회 실패 / 시드 미존재면 null. */
    fun find(): Guide? {
        if (!props.enabled) return null
        val id = identityClient.fetchIdentity() ?: return null
        if (!id.enabled || id.workspaceId == null) return null

        // 시드 소유자가 persona 로 분산되므로 owner 무관으로 데모 WS 를 제목 매칭 조회(봇 전제 제거).
        val caseId = errorCaseRepository.search(
            ErrorCaseSearchCriteria(
                workspaceId = id.workspaceId, ownerUserId = null, status = null, fingerprint = null,
                visibility = null, cursorCreatedAt = null, cursorId = null, limit = SEARCH_LIMIT,
            ),
        ).firstOrNull { it.title == DemoSeeder.GUIDE_CASE_TITLE }?.id ?: return null

        return Guide(caseId = caseId, articleSlug = props.guideArticleSlug)
    }

    data class Guide(val caseId: Long, val articleSlug: String)

    private companion object {
        // 봇 시드는 15건 안팎 — 여유 있게.
        const val SEARCH_LIMIT = 100
    }
}

package org.studieojavry.coreapi.demo.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility

/**
 * 데모 워크스페이스 시드 콘텐츠 — **데모 봇 소유**의 그럴듯한 에러 케이스 몇 건.
 * 가시성은 WORKSPACE(데모 WS 내부 전용) — 전역 PUBLIC 피드로 새지 않는다.
 * 리셋 때 wipe 후 재생성되므로 항상 동일한 베이스라인을 보장한다.
 */
@Service
class DemoSeeder(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
) {

    /** 데모 WS 에 케이스가 하나도 없을 때만 시드(기동 시 populate 용). 생성 건수 반환. */
    @Transactional
    fun ensureSeeded(workspaceId: Long, botUserId: Long): Int {
        val existing = errorCaseRepository.search(criteria(workspaceId, limit = 1))
        if (existing.isNotEmpty()) return 0
        return seedFresh(workspaceId, botUserId)
    }

    /** 무조건 시드 세트를 생성(리셋의 wipe 직후 호출). 생성 건수 반환. */
    @Transactional
    fun seedFresh(workspaceId: Long, botUserId: Long): Int {
        SEED.forEach { s ->
            errorCaseRepository.save(
                ErrorCase.create(
                    ownerUserId = botUserId,
                    title = s.title,
                    project = s.project,
                    snapshot = null,
                    description = s.description,
                    meta = Meta.create(workspaceId = workspaceId, severityCode = null),
                    visibility = Visibility.WORKSPACE,
                    snippets = emptyList(),
                    attachments = emptyList(),
                    tags = s.tags,
                    occurredAt = null,
                ),
            )
        }
        return SEED.size
    }

    private fun criteria(workspaceId: Long, limit: Int) = ErrorCaseSearchCriteria(
        workspaceId = workspaceId,
        ownerUserId = null,
        status = null,
        fingerprint = null,
        visibility = null,
        cursorCreatedAt = null,
        cursorId = null,
        limit = limit,
    )

    private data class Seed(
        val title: String,
        val project: String,
        val description: String,
        val tags: List<String>,
    )

    companion object {
        private val SEED = listOf(
            Seed(
                title = "로그인 후 프로필 로딩 시 NullPointerException",
                project = "core-api",
                description = "OAuth 로그인 직후 홈 대시보드 진입에서 간헐적으로 NPE 발생. 아바타 URL 이 null 인 계정에서 " +
                    "프로필 하이드레이션 중 터지는 것으로 추정. 재현율은 신규 가입자에서 높음.",
                tags = listOf("kotlin", "npe", "oauth"),
            ),
            Seed(
                title = "PDF 공개 export 중 OutOfMemoryError",
                project = "publish-api",
                description = "공개 아티클 PDF 렌더(OpenHTMLToPdf)에서 이미지가 많은 문서를 뽑을 때 1 CPU 인스턴스가 " +
                    "힙을 다 써 OOM. 동시 요청이 겹치면 더 빨리 터짐. 렌더 큐/페이지네이션 검토 필요.",
                tags = listOf("jvm", "oom", "pdf"),
            ),
            Seed(
                title = "알림 SSE 연결이 30초마다 끊김",
                project = "noti-api",
                description = "EventSource 가 약 30초 간격으로 재연결. 게이트웨이/프록시 idle timeout 과 heartbeat 주기 " +
                    "불일치 의심. keep-alive 코멘트 프레임 주기를 timeout 보다 짧게 조정하면 해결될 것으로 보임.",
                tags = listOf("sse", "timeout", "realtime"),
            ),
            Seed(
                title = "댓글 멘션 자동완성이 대소문자에 민감",
                project = "core-api",
                description = "@멘션 입력 시 핸들을 대문자로 치면 자동완성 후보가 안 뜸. 저장은 소문자 정규화인데 검색 쿼리가 " +
                    "case-sensitive 로 나가는 것으로 보임. ignore-case 매칭으로 통일 필요.",
                tags = listOf("search", "ux", "mention"),
            ),
            Seed(
                title = "DLQ 재처리 배치가 간헐적으로 정체",
                project = "insight-api",
                description = "이벤트 소비 실패분이 DLQ 로 가는데, 재처리 배치가 특정 배치 크기에서 멈춰 적체. " +
                    "배치 크기/인터벌을 설정값으로 빼서 운영 중 튜닝 가능하게 바꾼 뒤 관찰 중.",
                tags = listOf("kafka", "dlq", "batch"),
            ),
        )
    }
}

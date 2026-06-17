package org.studieojavry.coreapi.errorcase.case.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.command.UpdateErrorCaseCommand
import org.studieojavry.coreapi.errorcase.snippet.application.port.CodeSnippetRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.CaseWatchlistRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseTagRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorSnaphostExtractorPort
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorSnapshot
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.RawStackTrace
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import org.studieojavry.coreapi.errorcase.shared.application.port.ActivityEventPublisherPort
import org.studieojavry.coreapi.errorcase.shared.application.port.NotificationPublisherPort
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceMemberReaderPort
import org.studieojavry.coreapi.errorcase.shared.application.usecase.ErrorCaseAccess
import org.studieojavry.coreapi.shared.util.FingerprintGenerator
import java.time.Instant

/**
 * 에러케이스 부분 수정 — **소유자만**.
 * null 필드는 변경 안 함. `paste` 가 오면 스냅샷/지문을 재추출한다.
 *
 * 스니펫·첨부는 **선언형 재연결**: 요청의 marker 집합을 "최종 상태"로 보고 diff 한다.
 *  - null  → 유지(건드리지 않음)
 *  - []    → 전부 연결 해제(→ orphan → GC)
 *  - [..]  → 그 집합이 되도록 추가/해제
 * (워크스페이스 이동은 범위 밖)
 */
@Service
class UpdateErrorCaseUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val snippetRepository: CodeSnippetRepositoryPort,
    private val attachmentRepository: ErrorCaseAttachmentRepositoryPort,
    private val tagRepository: ErrorCaseTagRepositoryPort,
    private val errorSnapshotExtractor: ErrorSnaphostExtractorPort,
    private val access: ErrorCaseAccess,
    private val caseWatchlistRepository: CaseWatchlistRepositoryPort,
    private val workspaceMemberReader: WorkspaceMemberReaderPort,
    private val notificationPublisher: NotificationPublisherPort,
    private val activityEventPublisher: ActivityEventPublisherPort,
    private val cacheInvalidator: org.studieojavry.coreapi.shared.config.DashboardCacheInvalidator,
) {
    @Transactional
    fun invoke(command: UpdateErrorCaseCommand): ErrorCase {
        val errorCase = errorCaseRepository.findById(command.errorCaseId)
            ?: throw ErrorCaseNotFoundException(command.errorCaseId)

        if (errorCase.ownerUserId != command.requesterUserId) {
            throw ErrorCaseAccessDeniedException("only the owner can edit error case ${command.errorCaseId}")
        }

        // 워크스페이스 케이스의 PUBLIC 승격은 워크스페이스 ADMIN 만 (자산 외부 노출 정책).
        val newVisibility = command.visibility ?: errorCase.visibility
        if (
            errorCase.meta.workspaceId != null &&
            errorCase.visibility != Visibility.PUBLIC &&
            newVisibility == Visibility.PUBLIC
        ) {
            access.requirePublicPromotion(errorCase, command.requesterUserId)
        }

        val newMeta = Meta.create(
            workspaceId = errorCase.meta.workspaceId, // 워크스페이스 이동은 불가(범위 밖)
            severityCode = errorCase.meta.severity?.code, // MVP1: severity 유지만, 변경 X
        )
        val newSnapshot = if (command.paste != null) buildSnapshot(command.paste) else errorCase.snapshot

        errorCase.update(
            title = command.title ?: errorCase.title,
            project = command.project ?: errorCase.project,
            snapshot = newSnapshot,
            description = command.description ?: errorCase.description,
            meta = newMeta,
            visibility = newVisibility,
        )
        // 상태 전이는 도메인 transitionTo 가 검증. 같은 상태면 no-op.
        val prevStatus = errorCase.status
        command.status?.let { errorCase.transitionTo(it, command.requesterUserId) }
        errorCaseRepository.update(errorCase)

        // RESOLVED 로 전환됐으면 알림 발행 + 잔디 event. recipients = 워크스페이스 멤버 ∪ watchlist 사용자, actor 제외.
        if (prevStatus != ErrorCaseStatus.RESOLVED && errorCase.status == ErrorCaseStatus.RESOLVED) {
            publishCaseResolvedNotification(errorCase, command.requesterUserId)
            // 잔디용 activity event (fire-and-forget). 본인 액션이어도 *기여도* 신호로 가산.
            runCatching {
                activityEventPublisher.publish(
                    ActivityEventPublisherPort.ActivityEvent(
                        userId = command.requesterUserId,
                        type = ActivityEventPublisherPort.Type.CASE_RESOLVED,
                        occurredAt = Instant.now(),
                        idempotencyKey = "case-resolved:${errorCase.id}",
                        meta = mapOf("errorCaseId" to errorCase.id),
                    )
                )
            }
        }

        // 스니펫·첨부·태그 선언형 재설정(diff). 같은 트랜잭션.
        reconcileSnippets(command.snippetMarkerIds, command.errorCaseId, command.requesterUserId)
        reconcileAttachments(command.attachmentMarkerIds, command.errorCaseId, command.requesterUserId)
        reconcileTags(command.tags, command.errorCaseId)

        // 캐시 무효화 — case owner 의 recent-active (status 전이 / meta 변경 반영)
        // + actor 의 my-recent-activities (RESOLVED 전환 시 CASE_RESOLVED summary 반영. 단순화 위해 매 update 시 evict)
        runCatching { cacheInvalidator.evictRecentActive(errorCase.ownerUserId) }
        runCatching { cacheInvalidator.evictMyRecentActivities(command.requesterUserId) }

        // 재연결이 반영된 최신 애그리거트를 다시 로드해 반환(벌크 UPDATE 후 영속성 컨텍스트는 clear 됨).
        return errorCaseRepository.findById(command.errorCaseId)
            ?: throw ErrorCaseNotFoundException(command.errorCaseId)
    }

    /**
     * RESOLVED 알림 fan-out:
     *  - 워크스페이스 case: workspace 멤버 ∪ watchlist 사용자
     *  - 개인 PUBLIC case: watchlist 사용자만
     *  - 개인 PRIVATE case: actor==owner 라서 recipients 0 → 발행 skip
     *  - 모든 경우 actor 제외
     *
     * fire-and-forget — 실패 시 status 변경 자체는 성공으로 처리.
     */
    private fun publishCaseResolvedNotification(errorCase: ErrorCase, actorUserId: Long) {
        val caseId = errorCase.id ?: return
        val workspaceId = errorCase.meta.workspaceId
        val watchlistUserIds = runCatching { caseWatchlistRepository.findUserIdsByCaseId(caseId) }
            .getOrDefault(emptyList())
        val workspaceMemberIds = if (workspaceId != null) {
            runCatching { workspaceMemberReader.findMemberIds(workspaceId) }.getOrDefault(emptyList())
        } else emptyList()
        val recipients = (watchlistUserIds + workspaceMemberIds)
            .toSet()
            .minus(actorUserId)
            .toList()
        if (recipients.isEmpty()) return
        notificationPublisher.publishCaseResolved(
            NotificationPublisherPort.CaseResolvedEvent(
                recipientUserIds = recipients,
                actorUserId = actorUserId,
                errorCaseId = caseId,
                caseTitle = errorCase.title,
                workspaceId = workspaceId,
            )
        )
    }

    /**
     * desired(요청 marker 집합)와 current(현재 연결된 marker 집합)의 차집합으로 추가/해제를 계산한다.
     *  - toAdd = desired − current : 존재·소유·미연결 검증 후 연결
     *  - toRemove = current − desired : 연결 해제(→ orphan → GC)
     */
    private fun reconcileSnippets(desired: List<String>?, caseId: Long, ownerUserId: Long) {
        if (desired == null) return // 필드 미포함 → 유지

        val want = desired.distinct().toSet()
        val current = snippetRepository.findAllByErrorCaseId(caseId).map { it.markerId }.toSet()
        val toAdd = want - current
        val toRemove = current - want

        if (toAdd.isNotEmpty()) {
            val found = snippetRepository.findAllByMarkerIds(toAdd.toList())
            val foundIds = found.map { it.markerId }.toSet()
            val missing = toAdd - foundIds
            if (missing.isNotEmpty()) throw ErrorCaseLinkException("unknown snippet marker(s): $missing")
            found.forEach { s ->
                if (s.uploadedByUserId != ownerUserId) {
                    throw ErrorCaseLinkException("snippet ${s.markerId} is not owned by requester")
                }
                if (s.errorCaseId != null) {
                    throw ErrorCaseLinkException("snippet ${s.markerId} is already linked to another case")
                }
            }
            snippetRepository.linkToCase(toAdd.toList(), caseId)
        }
        if (toRemove.isNotEmpty()) {
            snippetRepository.unlinkFromCase(toRemove.toList())
        }
    }

    private fun reconcileAttachments(desired: List<String>?, caseId: Long, ownerUserId: Long) {
        if (desired == null) return

        val want = desired.distinct().toSet()
        val current = attachmentRepository.findAllByErrorCaseId(caseId).map { it.markerId }.toSet()
        val toAdd = want - current
        val toRemove = current - want

        if (toAdd.isNotEmpty()) {
            val found = attachmentRepository.findAllByMarkerIds(toAdd.toList())
            val foundIds = found.map { it.markerId }.toSet()
            val missing = toAdd - foundIds
            if (missing.isNotEmpty()) throw ErrorCaseLinkException("unknown attachment marker(s): $missing")
            found.forEach { a ->
                if (a.uploadedByUserId != ownerUserId) {
                    throw ErrorCaseLinkException("attachment ${a.markerId} is not owned by requester")
                }
                if (a.errorCaseId != null) {
                    throw ErrorCaseLinkException("attachment ${a.markerId} is already linked to another case")
                }
            }
            attachmentRepository.linkToCase(toAdd.toList(), caseId)
        }
        if (toRemove.isNotEmpty()) {
            attachmentRepository.unlinkFromCase(toRemove.toList())
        }
    }

    /**
     * 태그 선언형 재설정. snippet/attachment 와 동일한 diff 패턴 — null=유지, []=전부 제거, [..]=치환.
     * 각 raw tag 는 `ErrorCase.normalizeTag` 로 정규화(trim·소문자·≤32, blank 무시). 결과 집합 크기 max 20 검증.
     */
    private fun reconcileTags(desired: List<String>?, caseId: Long) {
        if (desired == null) return

        val want: Set<String> = desired
            .mapNotNull { ErrorCase.normalizeTag(it) }
            .toSet()
        if (want.size > ErrorCase.TAGS_MAX_PER_CASE) {
            throw ErrorCaseLinkException("tag count exceeded: max ${ErrorCase.TAGS_MAX_PER_CASE} per case (got ${want.size})")
        }
        val current = tagRepository.findAllByErrorCaseId(caseId).toSet()
        (current - want).forEach { tagRepository.remove(caseId, it) }
        (want - current).forEach { tagRepository.add(caseId, it) }
    }

    private fun buildSnapshot(paste: String): ErrorSnapshot? {
        val extracted = errorSnapshotExtractor.extract(paste)
        val rawStackTrace = extracted.rawStackTrace?.takeIf { it.isNotBlank() }?.let { RawStackTrace(it) }
        val fingerprint = FingerprintGenerator.generate(
            exceptionClass = extracted.exceptionClass,
            rawStackTrace = rawStackTrace
        )
        val snapshot = ErrorSnapshot(
            rawPaste = paste,
            exceptionClass = extracted.exceptionClass,
            exceptionMessage = extracted.exceptionMessage,
            rawStackTrace = rawStackTrace,
            fingeprint = fingerprint
        )
        return snapshot.takeUnless { it.isEmpty() }
    }
}

/** 스니펫/첨부 재연결 요청이 유효하지 않음(미존재·타인 소유·이미 다른 케이스에 연결). → 400. */
class ErrorCaseLinkException(message: String) : RuntimeException(message)

package org.studieojavry.coreapi.errorcase.case.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.AttachmentDeleter
import org.studieojavry.coreapi.errorcase.case.application.port.CaseMeTooRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.CaseViewRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.CaseWatchlistRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseTagRepositoryPort
import org.studieojavry.coreapi.errorcase.comment.application.port.CommentRepositoryPort
import org.studieojavry.coreapi.errorcase.snippet.application.port.CodeSnippetRepositoryPort
import org.studieojavry.coreapi.errorcase.snippet.application.usecase.SnippetDeleter
import org.studieojavry.coreapi.errorcase.solution.application.port.SolutionRepositoryPort
import org.studieojavry.coreapi.errorcase.step.application.port.StepRepositoryPort


/**
 * 에러 케이스 삭제 + 연결된 첨부/스니펫 cascade.
 *
 * **자식 먼저 → 케이스** 순서로, 각 자식은 단일 삭제 루틴([AttachmentDeleter]/[SnippetDeleter])을
 * 재사용해 지운다. 일부러 클래스 레벨 @Transactional 을 두지 않는다:
 *  - 첨부 삭제는 파일(비트랜잭셔널)+row 라, 큰 트랜잭션으로 묶으면 롤백 시 "파일은 지워졌는데 row 는
 *    남아 errorCaseId 가 채워진 채" 가 되어 orphan GC(errorCaseId IS NULL)에도 안 잡히는 깨진 상태가 된다.
 *  - 각 Deleter 가 독립 커밋·멱등이므로, 중간 실패해도 재시도하면 남은 자식+케이스를 다시 찾아 마무리(자기치유).
 *
 * 자식이 모두 지워진 뒤에만 케이스를 지운다(자식이 남았는데 케이스를 지우면 그 자식이 leaked orphan
 * 이 되므로, 자식 삭제 실패 시 예외를 전파해 케이스는 보존 → 재시도).
 */
@Service
class DeleteErrorCaseUseCase(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
    private val attachmentRepository: ErrorCaseAttachmentRepositoryPort,
    private val snippetRepository: CodeSnippetRepositoryPort,
    private val attachmentDeleter: AttachmentDeleter,
    private val snippetDeleter: SnippetDeleter,
    private val stepRepository: StepRepositoryPort,
    private val solutionRepository: SolutionRepositoryPort,
    private val commentRepository: CommentRepositoryPort,
    private val tagRepository: ErrorCaseTagRepositoryPort,
    private val meTooRepository: CaseMeTooRepositoryPort,
    private val caseViewRepository: CaseViewRepositoryPort,
    private val caseWatchlistRepository: CaseWatchlistRepositoryPort,
    private val cacheInvalidator: org.studieojavry.coreapi.shared.config.DashboardCacheInvalidator,
) {
    private val log = KotlinLogging.logger {}

    /**
     * `@Transactional` 은 *JPQL `@Modifying` DELETE 가 한 transactional context 안에서 실행되어야* 하므로 필요.
     * AttachmentDeleter/SnippetDeleter 는 자체 `@Transactional` 메서드 — PROPAGATION_REQUIRED 디폴트라 본 메서드의 부모 transaction inlining.
     */
    @Transactional
    fun invoke(errorCaseId: Long, requesterUserId: Long) {
        val ownerUserId = errorCaseRepository.findOwnerUserIdById(errorCaseId)
            ?: throw ErrorCaseNotFoundException(errorCaseId)
        if (ownerUserId != requesterUserId) {
            throw ErrorCaseDeleteForbiddenException("case owner != requester (caseId=$errorCaseId)")
        }

        // 자식 먼저 (단일 삭제 루틴 재사용). 한 건이라도 실패하면 예외 전파 → 케이스 보존 → 재시도.
        attachmentRepository.findAllByErrorCaseId(errorCaseId).forEach { attachmentDeleter.delete(it) }
        snippetRepository.findAllByErrorCaseId(errorCaseId).forEach { snippetDeleter.delete(it) }

        // step/solution 은 파일이 없으니 단순 bulk 삭제. **solution 먼저(step 참조)** → step.
        solutionRepository.deleteAllByErrorCaseId(errorCaseId)
        stepRepository.deleteAllByErrorCaseId(errorCaseId)

        // 댓글(+ 자식 reaction/helpful/mention 은 외래키 cascade 가 없으니 직접 정리해야 안전)
        commentRepository.findAllByErrorCaseId(errorCaseId).forEach {
            commentRepository.deleteAllReactionsByCommentId(it.id!!)
            commentRepository.deleteAllHelpfulByCommentId(it.id)
            commentRepository.deleteAllMentionsByCommentId(it.id)
        }
        commentRepository.deleteAllByErrorCaseId(errorCaseId)

        // 태그 cascade (DB FK 가 ON DELETE CASCADE 라 자동 정리되지만 안전망)
        tagRepository.deleteAllByErrorCaseId(errorCaseId)

        // "나도 겪었어요" cascade
        meTooRepository.deleteAllByCaseId(errorCaseId)

        // case_view (사용자별 마지막 조회 시각) + case_watchlist (즐겨찾기) cascade
        caseViewRepository.deleteAllByCaseId(errorCaseId)
        caseWatchlistRepository.deleteAllByCaseId(errorCaseId)

        errorCaseRepository.deleteById(errorCaseId)
        log.info { "[error-case] deleted caseId=$errorCaseId (cascade attachments/snippets/steps/solutions/comments/tags/me-too/view/watchlist) by user=$requesterUserId" }

        // 캐시 무효화 — 삭제 후 owner 의 recent-active + my-recent-activities 갱신 필수
        // (삭제된 case 관련 activity 항목이 사라져야 함)
        runCatching { cacheInvalidator.evictRecentActive(ownerUserId) }
        runCatching { cacheInvalidator.evictMyRecentActivities(ownerUserId) }
    }
}

class ErrorCaseNotFoundException(val errorCaseId: Long) :
    RuntimeException("error case not found: id=$errorCaseId")

class ErrorCaseDeleteForbiddenException(message: String) : RuntimeException(message)

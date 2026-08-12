package org.studieojavry.coreapi.errorcase.attachment.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment

/**
 * 첨부 1건을 삭제하는 **단일 루틴**. orphan GC / 명시적 삭제 / 케이스 삭제 cascade(DeleteErrorCaseUseCase)가
 * 모두 이걸 재사용해 삭제 경로가 갈라지지 않게 한다.
 *
 * 순서·멱등성:
 *  - **파일 먼저 → row** 순서. 파일 삭제는 멱등(`deleteIfExists`)이라 이미 없어도 OK.
 *  - row 삭제가 실패해 트랜잭션이 롤백되면 파일은 사라졌지만 row 는 남는다 → 다음 GC 가
 *    같은 미연결 row 를 다시 집어 파일은 이미 없으니 그대로 row 만 정리 (자기치유).
 *  - 다중 인스턴스가 동시에 같은 첨부를 지워도 둘 다 멱등이라 무해.
 *
 * 각 호출이 독립 트랜잭션(@Transactional)이라, GC 배치 루프에서 한 건 실패가 다른 건을
 * 롤백시키지 않는다.
 */
@Service
class AttachmentDeleter(
    private val repository: ErrorCaseAttachmentRepositoryPort,
    private val storage: AttachmentStoragePort,
) {
    private val log = KotlinLogging.logger {}

    @Transactional
    fun delete(attachment: Attachment) {
        storage.delete(attachment.objectKey)
        repository.deleteByMarkerId(attachment.markerId)
        log.debug { "[attachment] deleted markerId=${attachment.markerId} (errorCaseId=${attachment.errorCaseId})" }
    }
}

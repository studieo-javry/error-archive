package org.studieojavry.coreapi.errorcase.attachment.application.port

import org.springframework.stereotype.Repository

@Repository
interface AttachmentStoragePort {

    fun store(
        markerId: String,
        fileName: String,
        bytes: ByteArray,
        contentType: String
    ): StoredAttachment

    fun load(markerId: String, fileName: String): ByteArray

    /**
     * 첨부 파일 삭제. **멱등** — 파일이 이미 없어도 예외 없이 정상 반환해야 한다.
     * (GC/명시삭제가 재시도되거나 다중 인스턴스에서 동시 호출돼도 안전하도록.)
     */
    fun delete(markerId: String, fileName: String)

    data class StoredAttachment(
        val storageUrl: String
    )
}

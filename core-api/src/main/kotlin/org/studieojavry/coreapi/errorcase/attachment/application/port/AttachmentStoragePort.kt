package org.studieojavry.coreapi.errorcase.attachment.application.port

import org.springframework.stereotype.Repository
import java.net.URI
import java.time.Duration

/**
 * 첨부 파일 스토리지. S3 호환(AWS S3 / MinIO) 구현으로 교체됨.
 *
 * 이미지 렌더는 [presignGet] 이 발급하는 **서명된 임시 URL** 을 브라우저가 직접 `<img src>` 로 때린다
 * (앱 서버는 이미지 트래픽에서 빠짐). objectKey 생성은 호출측(UseCase) 책임 — 스토리지는 저장/서명만.
 */
@Repository
interface AttachmentStoragePort {

    /** 업로드. contentType 을 오브젝트 메타로 저장해야 브라우저가 inline 렌더한다. */
    fun put(objectKey: String, bytes: ByteArray, contentType: String)

    /**
     * 서명된 임시 GET URL. [disposition] 으로 inline(렌더)/attachment(다운로드) 분리
     * (S3 `response-content-disposition` override). URL 자체엔 사용자 검사가 없으므로,
     * **가시성 검사는 이 메서드를 호출하기 전(발급 시점)에** 끝나 있어야 한다.
     */
    fun presignGet(objectKey: String, disposition: Disposition, ttl: Duration): URI

    /** 서버측에서 바이트가 필요할 때(예: PDF 렌더). 이미지 렌더 경로에는 안 씀. */
    fun getBytes(objectKey: String): ByteArray

    /**
     * 오브젝트 삭제. **멱등** — 이미 없어도 예외 없이 정상 반환해야 한다.
     * (GC/명시삭제 재시도, 다중 인스턴스 동시 호출에도 안전하도록.)
     */
    fun delete(objectKey: String)

    enum class Disposition { INLINE, ATTACHMENT }
}

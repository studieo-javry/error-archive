package org.studieojavry.iamapi.auth.application.port

import org.springframework.stereotype.Repository

/**
 * 사용자 아바타 이미지 저장소. local FS / S3 / GCS 등 인프라 교체 가능.
 */
@Repository
interface AvatarStoragePort {

    /**
     * 새 아바타 파일 저장. *덮어쓰기 X* — 같은 사용자가 다시 업로드해도 새 파일명(uuid)으로 저장.
     * 이전 파일 정리는 호출자 책임(원래 url 을 같이 보존했다가 `delete` 호출).
     *
     * @return 저장 후 *공개 URL* + *내부 식별자* (이전 파일 정리에 사용)
     */
    fun store(userId: Long, originalFileName: String?, contentType: String, bytes: ByteArray): StoredAvatar

    /**
     * 공개 URL 로부터 *내부 파일 식별자* 를 역추적해 디스크에서 삭제. **멱등** — 파일이 이미 없어도 OK.
     * 우리 storage URL 이 아닌 외부 URL 이면 *조용히 무시*.
     */
    fun delete(publicUrl: String)

    data class StoredAvatar(
        val publicUrl: String,
    )
}

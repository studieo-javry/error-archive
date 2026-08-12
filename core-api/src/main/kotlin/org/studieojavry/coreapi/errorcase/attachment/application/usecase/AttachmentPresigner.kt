package org.studieojavry.coreapi.errorcase.attachment.application.usecase

import org.springframework.stereotype.Service
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import java.time.Duration

/**
 * SE 객체는 다음과 같은 형태로 저장
 * - bucket: error-archive-prod
 * - objectKey: attachments/2026/07/abc123.png -- S3 안에서 파일을 식별하는 경로 같은 값
 * S3가 private bucket이라면 사용자는 다음 주소로 직접 접근이 불가
 * - https://error-archive-prod.s3.amazonaws.com/attachments/2026/07/abc123.png
 * -> 해당 객체가 공개되지 않았기 때문
 * -> 그래서 서버가 S3 호환 스토리지 자격증명(prod=OCI Object Storage / stg·local=MinIO)을 이용해서,
 *    일정 시간 동안만 유효한 임시 접근 URL을 발급 -- Presigned URL
 * -> 해당 URL을 받은 사용자는 스토리지 인증정보를 직접 가지지 않아도, 유효한 동안 해당 객체를 업로드/조회가 가능
 *
 * Presigned URL은 크게 두 종류 -- 조회용 / 업로드용
 * 1. 다운로드·조회용 Presigned GET URL
 * - 이미 S3에 있는 파일 조회 시 사용
 *       클라이언트
 *          │ objectKey 전달
 *          ▼
 *         백엔드
 *          │ Presigned GET URL 생성
 *          ▼
 *        클라이언트
 *          │ URL로 S3 직접 요청
 *          ▼
 * S3 객체 다운로드 또는 화면 표시
 *
 * 2. 업로드용 Presigned PUT URL
 *       클라이언트
 *          │ 파일명, 타입 등 전달
 *          ▼
 *         백엔드
 *          │ objectKey 생성
 *          │ Presigned PUT URL 생성
 *          ▼
 *        클라이언트
 *          │ PUT URL로 파일 업로드
 *          ▼
 *          S3
 *
 * objectKey → presigned URL 발급 얇은 래퍼. 상세 조회(inline+download) 와 업로드 미리보기(inline)가 공유.
 * 가시성 검사는 **호출측**이 이 서비스를 부르기 전에 끝내야 한다(포트 계약 참고).
 */
@Service
class AttachmentPresigner(
    private val storage: AttachmentStoragePort,
) {
    fun viewUrl(objectKey: String, ttl: Duration): String =
        storage.presignGet(objectKey, AttachmentStoragePort.Disposition.INLINE, ttl).toString()

    fun downloadUrl(objectKey: String, ttl: Duration): String =
        storage.presignGet(objectKey, AttachmentStoragePort.Disposition.ATTACHMENT, ttl).toString()
}

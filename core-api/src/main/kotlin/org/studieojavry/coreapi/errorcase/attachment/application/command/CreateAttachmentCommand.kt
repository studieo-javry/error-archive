package org.studieojavry.coreapi.errorcase.attachment.application.command

data class CreateAttachmentCommand(
    val uploaderUserId: Long,
    val title: String?,
    val caption: String?,
    val fileName: String,
    val contentType: String,
    val size: Long,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CreateAttachmentCommand

        if (uploaderUserId != other.uploaderUserId) return false
        if (size != other.size) return false
        if (title != other.title) return false
        if (caption != other.caption) return false
        if (fileName != other.fileName) return false
        if (contentType != other.contentType) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = uploaderUserId.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (caption?.hashCode() ?: 0)
        result = 31 * result + fileName.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

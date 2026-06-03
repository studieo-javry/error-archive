package org.studieojavry.internalauth

import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * PEM 인코딩된 RSA 키를 파싱한다.
 *  - 개인키: PKCS#8  (-----BEGIN PRIVATE KEY-----)
 *  - 공개키: X.509 SubjectPublicKeyInfo (-----BEGIN PUBLIC KEY-----)
 *
 * 메쉬 도입 후엔 키 배포가 메쉬(SPIFFE)로 이관되므로, 여기서 PEM 을 직접 다루는 코드는
 * 그 시점에 제거 대상이다. JWKS endpoint 로 가도 검증 로직(서명 알고리즘)은 동일하게 유지된다.
 */
object PemKeyParser {

    fun parsePrivateKey(pem: String): RSAPrivateKey {
        val der = decodeBody(pem, "PRIVATE KEY")
        val spec = PKCS8EncodedKeySpec(der)
        return KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey
    }

    fun parsePublicKey(pem: String): RSAPublicKey {
        val der = decodeBody(pem, "PUBLIC KEY")
        val spec = X509EncodedKeySpec(der)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    private fun decodeBody(pem: String, label: String): ByteArray {
        val body = pem
            .replace("-----BEGIN $label-----", "")
            .replace("-----END $label-----", "")
            .replace("\\s".toRegex(), "")
        require(body.isNotBlank()) { "Empty PEM body for $label" }
        return Base64.getDecoder().decode(body)
    }
}

package org.studieojavry.publishapi.publishment.application.usecase

import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.studieojavry.publishapi.shared.config.PagePublicProperties
import org.studieojavry.publishapi.shared.config.PublicPageCacheConfig

/**
 * 공개 페이지 HTML 렌더 캐시.
 *
 *  - key = `slug@stamp`(stamp = 발행물 updatedAt). 발행/메타편집은 모두 updatedAt 을 갱신하므로
 *    내용이 바뀌면 새 key 로 자동 재렌더. 조회수/다운로드 증가는 updatedAt 을 안 건드려 캐시 무효화 X.
 *  - **전체 엔티티 로드(content_json jsonb 역직렬화) + 렌더는 캐시 MISS 안에서만** 수행한다.
 *    HIT 시엔 이 메서드가 호출되지 않으므로, 컨트롤러의 경량 메타 조회만으로 응답 → DB 조회+역직렬화+렌더를 모두 건너뜀.
 *    (렌더만 캐시하고 매 요청 전체 로드하던 이전 버전은 요청당 CPU 대부분을 차지하는 로드/역직렬화를 못 줄였다.)
 *  - 웹 렌더(forPdf=false)는 첨부를 안정 라우트로 처리하고 `imageEmbedUrl`(presign)을 쓰지 않으므로
 *    결과 HTML 은 발행물에 대한 순수함수 → 같은 stamp 면 항상 동일 HTML → 캐시 안전.
 */
@Service
class PublicPageRenderCache(
    private val getBySlugUseCase: GetPublishmentBySlugUseCase,
    private val pageProps: PagePublicProperties,
) {
    @Cacheable(cacheNames = [PublicPageCacheConfig.PUBLIC_PAGE_CACHE], key = "#slug + '@' + #stamp")
    fun render(slug: String, stamp: String): String {
        // MISS 경로에서만 실행: 전체 엔티티 로드(jsonb 역직렬화) + 렌더.
        val p = getBySlugUseCase.invoke(slug, viewerUserId = null)
        return HtmlRenderer.render(
            p,
            forPdf = false,
            publicBaseUrl = pageProps.publicBaseUrl,
            imageEmbedUrl = { null }, // 웹 렌더에선 미사용(안정 라우트 사용)
        )
    }
}

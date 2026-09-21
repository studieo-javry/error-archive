package org.studieojavry.publishapi.publishment.application.usecase

import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.studieojavry.publishapi.publishment.domain.CasePublishment
import org.studieojavry.publishapi.shared.config.PagePublicProperties
import org.studieojavry.publishapi.shared.config.PublicPageCacheConfig

/**
 * 공개 페이지 HTML 렌더 캐시.
 *
 *  - key = `slug@stamp`(stamp = 발행물 updatedAt). 발행/메타편집은 모두 updatedAt 을 갱신하므로
 *    내용이 바뀌면 새 key 로 자동 재렌더된다. 조회수/다운로드 증가는 updatedAt 을 건드리지 않아 캐시 무효화 X.
 *  - 웹 렌더(forPdf=false)는 첨부를 **안정 라우트**로 처리하고 `imageEmbedUrl`(presign)을 쓰지 않으므로,
 *    결과 HTML 은 `p` 에 대한 순수함수 → 캐시해도 안전(같은 stamp 면 항상 동일 HTML).
 */
@Service
class PublicPageRenderCache(
    private val pageProps: PagePublicProperties,
) {
    @Cacheable(cacheNames = [PublicPageCacheConfig.PUBLIC_PAGE_CACHE], key = "#slug + '@' + #stamp")
    fun render(slug: String, stamp: String, p: CasePublishment): String =
        HtmlRenderer.render(
            p,
            forPdf = false,
            publicBaseUrl = pageProps.publicBaseUrl,
            imageEmbedUrl = { null }, // 웹 렌더에선 미사용(안정 라우트 사용)
        )
}

package org.studieojavry.gateway.filter

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.studieojavry.internalauth.InternalTokenIssuer
import kotlin.test.assertEquals

/**
 * HeaderInjectionFilter.resolveAudience 가 검토 API(/api/v1/captures, 웹 세션 사용자·core-api)를
 * aud=core-api 로 매핑하는지 확인한다. (누락 시 aud=iam-api → core-api 401. RouteConfig.coreApiRoute 와 정합.)
 */
class ReviewAudienceMappingTest {

    private val filter = HeaderInjectionFilter(mock(InternalTokenIssuer::class.java))

    @Test
    fun `검토 API 경로는 core-api aud 로 매핑된다`() {
        assertEquals(HeaderInjectionFilter.AUDIENCE_CORE_API, filter.resolveAudience("/api/v1/captures"))
        assertEquals(HeaderInjectionFilter.AUDIENCE_CORE_API, filter.resolveAudience("/api/v1/captures/9a7f/import"))
        assertEquals(HeaderInjectionFilter.AUDIENCE_CORE_API, filter.resolveAudience("/api/v1/captures/9a7f/discard"))
    }

    @Test
    fun `프로젝트 바인딩 관리·기존 Case 귀속 경로는 core-api aud 로 매핑된다`() {
        assertEquals(HeaderInjectionFilter.AUDIENCE_CORE_API, filter.resolveAudience("/api/v1/me/project-bindings"))
        assertEquals(HeaderInjectionFilter.AUDIENCE_CORE_API, filter.resolveAudience("/api/v1/me/cases/123/project-binding"))
    }

    @Test
    fun `기존 매핑은 그대로 — core 기존 경로·iam 폴백 sanity`() {
        assertEquals(HeaderInjectionFilter.AUDIENCE_CORE_API, filter.resolveAudience("/api/v1/error-cases/1"))
        // 에이전트 커넥터 발급 경로는 iam 소속(여기선 세션 사용자 검토 API 와 구분).
        assertEquals(HeaderInjectionFilter.AUDIENCE_IAM_API, filter.resolveAudience("/api/v1/me/agent-connections"))
        assertEquals(HeaderInjectionFilter.AUDIENCE_IAM_API, filter.resolveAudience("/api/v1/users/profile"))
    }
}

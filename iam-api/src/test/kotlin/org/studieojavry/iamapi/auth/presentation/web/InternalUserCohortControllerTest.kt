package org.studieojavry.iamapi.auth.presentation.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.domain.model.User
import java.time.Duration
import java.time.Instant

/**
 * `/internal/users/signups`(가입 코호트) 슬라이스 검증 — 실제 Spring context(H2) + MockMvc.
 * 컨트롤러의 파라미터 파싱(since ISO-8601·400)·비게스트/since 필터(파생쿼리)·JSON 모양을 한 번에 확인.
 * 모노레포 컨벤션(core-api)과 동일하게 webAppContextSetup 사용 — 기본적으로 보안필터 미적용이라
 * authenticated 인 internal 경로도 컨트롤러 로직만 직접 호출된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InternalUserCohortControllerTest(
    @Autowired private val context: WebApplicationContext,
    @Autowired private val users: UserRepositoryPort,
) {
    private val mockMvc = MockMvcBuilders.webAppContextSetup(context).build()

    @Test
    fun `since 이후 비게스트 가입자만 userId+createdAt 으로 반환한다`() {
        users.save(User.create(email = null, handle = "signup-aaa", displayName = "A", avatarUrl = null))
        users.save(User.create(email = null, handle = "signup-bbb", displayName = "B", avatarUrl = null))
        // 게스트는 제외돼야 한다 (3건 저장 → 2건만 반환)
        users.save(User.createGuest(handle = "guest-zzz", displayName = "G"))

        val since = Instant.now().minus(Duration.ofHours(1)).toString()

        mockMvc.perform(get("/internal/users/signups").param("since", since))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))      // 게스트 제외 증명
            .andExpect(jsonPath("$[0].userId").exists())
            .andExpect(jsonPath("$[0].createdAt").exists())
    }

    @Test
    fun `미래 since 는 빈 목록 — since 필터가 적용된다`() {
        users.save(User.create(email = null, handle = "signup-ccc", displayName = "C", avatarUrl = null))
        val future = Instant.now().plus(Duration.ofHours(1)).toString()

        mockMvc.perform(get("/internal/users/signups").param("since", future))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `since 가 ISO-8601 아니면 400`() {
        mockMvc.perform(get("/internal/users/signups").param("since", "not-a-date"))
            .andExpect(status().isBadRequest())
    }
}

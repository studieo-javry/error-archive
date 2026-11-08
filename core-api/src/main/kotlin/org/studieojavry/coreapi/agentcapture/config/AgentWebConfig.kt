package org.studieojavry.coreapi.agentcapture.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.studieojavry.coreapi.agentcapture.presentation.web.AgentPrincipalArgumentResolver

/** agent 전용 컨트롤러 인자(AgentPrincipal) 리졸버 등록. */
@Configuration
class AgentWebConfig : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(AgentPrincipalArgumentResolver())
    }
}

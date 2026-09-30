package org.studieojavry.coreapi.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 조회 usecase 의 **독립 fan-out**(서로 의존 없는 다중 조회)을 동시에 실행하기 위한 executor.
 *
 * 스택이 servlet/MVC(블로킹 RestClient·JDBC)라 reactive 전환 대신 **가상 스레드(JVM 25)** 로
 * 블로킹 콜을 동시화한다(지연 = 합 → 최댓값). `DelegatingSecurityContextExecutorService` 로
 * 감싸 자식 스레드에도 SecurityContext(내부 인증 토큰 발급용 사용자 컨텍스트)를 전파한다.
 */
@Configuration
class FanoutConfig {
    @Bean(name = ["fanoutExecutor"], destroyMethod = "close")
    fun fanoutExecutor(): ExecutorService =
        DelegatingSecurityContextExecutorService(Executors.newVirtualThreadPerTaskExecutor())
}

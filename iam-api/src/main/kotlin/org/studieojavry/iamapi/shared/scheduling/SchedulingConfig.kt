package org.studieojavry.iamapi.shared.scheduling

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.studieojavry.iamapi.shared.infrastructure.outbox.OutboxProperties
import javax.sql.DataSource

/**
 * 스케줄링 + 분산 락(ShedLock) + outbox properties 등록.
 *
 * core-api 의 동일 컴포넌트와 같은 패턴. outbox relayer / cleanup 의 단일 leader 강제.
 *
 * core-api 는 `@ConfigurationPropertiesScan` 으로 OutboxProperties 자동 등록되지만,
 * iam-api 는 scan 설정이 없어 명시 `@EnableConfigurationProperties` 로 등록.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
@EnableConfigurationProperties(OutboxProperties::class)
class SchedulingConfig {

    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        )
}

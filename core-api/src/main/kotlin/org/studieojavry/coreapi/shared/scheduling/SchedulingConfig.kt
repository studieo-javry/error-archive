package org.studieojavry.coreapi.shared.scheduling

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

/**
 * 스케줄링 + 분산 락(ShedLock) 활성화.
 *
 * 다중 인스턴스로 떠도 `@SchedulerLock` 으로 보호된 잡은 한 시점에 한 인스턴스에서만 실행된다.
 * (삭제 루틴 자체가 멱등이라 정확성은 락 없이도 보장되지만, 락으로 중복 스캔/작업을 막아 효율을 높인다.)
 *
 * 락 저장소는 기존 Postgres 의 `shedlock` 테이블([ShedLockEntity]). `usingDbTime()` 으로
 * 인스턴스 간 시계 차이와 무관하게 DB 시간을 기준으로 락을 판단한다.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
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

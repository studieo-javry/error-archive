import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.allopen.gradle.AllOpenExtension

plugins {
  kotlin("jvm") version "2.3.20"
  kotlin("plugin.spring") version "2.3.20"
  id("org.springframework.boot") version "4.0.4"
  id("io.spring.dependency-management") version "1.1.7"
  kotlin("plugin.jpa") version "2.3.20"
}

group = "org.studieo-javry"
version = "0.0.1-SNAPSHOT"
description = "insight-api"

extra["springCloudVersion"] = "2025.1.1"

repositories {
  mavenLocal()
  mavenCentral()
}

dependencyManagement {
  imports {
    mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
  }
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-actuator")

  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

  implementation("io.micrometer:micrometer-tracing-bridge-brave")
  // Prometheus 메트릭 registry — /internal/actuator/prometheus 노출 (관측 스택 스크레이프).
  runtimeOnly("io.micrometer:micrometer-registry-prometheus")
  implementation("io.github.oshai:kotlin-logging-jvm:7.0.7")

  // gateway / core-api / iam-api 발급 internal JWT 검증 + iam-api 호출용 발급
  implementation("org.studieo-javry:shared-internal-auth:0.0.1-SNAPSHOT")
  // 공용 예외 처리 — GlobalExceptionHandler + ProblemDetailAuthenticationEntryPoint + masking + traceId
  implementation("org.studieo-javry:shared-error:0.0.1-SNAPSHOT")
  implementation("org.jetbrains.kotlin:kotlin-reflect")

  // Kafka consumer — user-activity.v1 토픽 수신
  implementation("org.springframework.kafka:spring-kafka")
  // ShedLock — 다중 인스턴스 시 cleanup / DLQ retry 잡의 단일 leader
  implementation("net.javacrumbs.shedlock:shedlock-spring:6.10.0")
  implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.10.0")

  // Boot 4 → Jackson 3
  implementation("tools.jackson.module:jackson-module-kotlin")
  // Flyway — stg/prod 스키마 마이그레이션 (ddl-auto=validate 와 함께). PG 15+ 는 database-postgresql 모듈 필요.
  // ★ Boot 4.0 은 autoconfig 를 모듈로 분리 → flyway-core 만으로는 마이그레이션 자동 실행 안 됨.
  //    spring-boot-flyway (FlywayAutoConfiguration) 없으면 stg/prod(validate) 에서 부팅 실패.
  implementation("org.springframework.boot:spring-boot-flyway")
  implementation("org.flywaydb:flyway-core")
  implementation("org.flywaydb:flyway-database-postgresql")
  runtimeOnly("org.postgresql:postgresql")
  testRuntimeOnly("com.h2database:h2")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configure<KotlinJvmProjectExtension> {
  jvmToolchain(25)
  compilerOptions {
    freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
  }
}

configure<AllOpenExtension> {
  annotation("jakarta.persistence.Entity")
  annotation("jakarta.persistence.MappedSuperclass")
  annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
  useJUnitPlatform()
}

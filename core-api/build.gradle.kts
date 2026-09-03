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
description = "core-api"

// Spring Cloud release train (Boot 4.0 매칭)
extra["springCloudVersion"] = "2025.1.1"

repositories {
  mavenLocal()
  mavenCentral()
}

// spring-cloud-dependencies BOM을 읽어 안에 정의된 dependency version들을 Gradle dependency management에 등록
dependencyManagement {
  imports {
    // bill of materials: 라이브러리들의 "호환 가능한 버전 조합" 제공
    mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
  }
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-cache")
  implementation("com.github.ben-manes.caffeine:caffeine")

  // OpenAPI/Swagger UI — v3.x 가 Spring Boot 4 + Jackson 3 지원.
  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

  // 내부 서비스 인증 — composite build 가 로컬 shared-internal-auth 로 substitute.
  implementation("org.studieo-javry:shared-internal-auth:0.0.1-SNAPSHOT")
  implementation("org.studieo-javry:shared-error:0.0.1-SNAPSHOT")
  implementation("io.micrometer:micrometer-tracing-bridge-brave")
  // Prometheus 메트릭 registry — /internal/actuator/prometheus 노출 (관측 스택 스크레이프).
  runtimeOnly("io.micrometer:micrometer-registry-prometheus")
  implementation("io.github.oshai:kotlin-logging-jvm:7.0.7")
  // CircuitBreaker (외부 호출 격리: iam-api)
  implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")
  implementation("org.jetbrains.kotlin:kotlin-reflect")

  // Kafka — 알림 도메인 이벤트 producer (mention 등)
  implementation("org.springframework.kafka:spring-kafka")

  // orphan 첨부 GC 스케줄러의 다중 인스턴스 안전 (분산 락)
  implementation("net.javacrumbs.shedlock:shedlock-spring:6.10.0")
  implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.10.0")
  // Kotlin data class 의 default value / nullable 타입을 Jackson 이 인식하게 함.
  // ⚠️ Spring Boot 4 부터 Jackson 3.x (groupId: tools.jackson) 로 마이그레이션됨.
  //    레거시 com.fasterxml.jackson.module 그룹은 2.x 라 ObjectMapper 에 등록되지 않음.
  implementation("tools.jackson.module:jackson-module-kotlin")

  // AWS SDK v2 — S3 호환 스토리지(운영 S3 / stg·local MinIO). presigned URL 로 이미지 직접 렌더.
  // `s3` 아티팩트가 S3Client + S3Presigner + 기본 apache http client 포함.
  implementation(platform("software.amazon.awssdk:bom:2.28.16"))
  implementation("software.amazon.awssdk:s3")

  // Flyway — stg/prod 스키마 마이그레이션 (ddl-auto=validate 와 함께). PG 15+ 는 database-postgresql 모듈 필요.
  // ★ Boot 4.0 은 autoconfig 를 모듈로 분리 → flyway-core 만으로는 마이그레이션 자동 실행 안 됨.
  //    spring-boot-flyway (FlywayAutoConfiguration) 없으면 stg/prod(validate) 에서 부팅 실패.
  implementation("org.springframework.boot:spring-boot-flyway")
  implementation("org.flywaydb:flyway-core")
  implementation("org.flywaydb:flyway-database-postgresql")
  runtimeOnly("org.postgresql:postgresql")
  testRuntimeOnly("com.h2database:h2")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
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

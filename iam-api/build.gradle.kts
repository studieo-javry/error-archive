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
description = "iam-api"

// Spring Cloud release train (Boot 4.0 매칭)
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
  implementation("org.springframework.boot:spring-boot-starter-mail")

  // OpenAPI/Swagger UI — v3.x 가 Spring Boot 4 + Jackson 3 지원.
  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

  implementation("io.micrometer:micrometer-tracing-bridge-brave")
  implementation("io.github.oshai:kotlin-logging-jvm:7.0.7")

  // 내부 서비스 인증 — composite build 가 로컬 shared-internal-auth 로 substitute.
  implementation("org.studieo-javry:shared-internal-auth:0.0.1-SNAPSHOT")
  implementation("org.studieo-javry:shared-error:0.0.1-SNAPSHOT")
  // CircuitBreaker (외부 호출 격리: GitHub OAuth)
  implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")
  implementation("org.jetbrains.kotlin:kotlin-reflect")

  // Kotlin data class default value / nullable 을 Jackson 이 인식하게.
  // Boot 4 → Jackson 3 이라 groupId 가 `tools.jackson.module`.
  implementation("tools.jackson.module:jackson-module-kotlin")
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

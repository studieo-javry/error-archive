import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
  kotlin("jvm") version "2.3.20"
  kotlin("plugin.spring") version "2.3.20"
  id("org.springframework.boot") version "4.0.4"
  id("io.spring.dependency-management") version "1.1.7"
}

group = "org.studieo-javry"
version = "0.0.1-SNAPSHOT"
description = "gateway"

// Spring Cloud release train — Boot 버전과 호환되는 release 명을 사용해야 한다.
// https://spring.io/projects/spring-cloud 의 매트릭스에서 정확한 명을 확인.
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
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

  // Spring Cloud Gateway Server MVC (servlet 기반 SCG)
  implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc")

  // OpenAPI/Swagger UI — 다운스트림(iam/core) 스펙 aggregator 용.
  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

  // Circuit Breaker (Resilience4j)
  implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")

  implementation("io.micrometer:micrometer-tracing-bridge-brave")
  implementation("io.github.oshai:kotlin-logging-jvm:7.0.7")
  implementation("org.jetbrains.kotlin:kotlin-reflect")

  // 내부 서비스 인증 — composite build 가 로컬 shared-internal-auth 로 substitute.
  implementation("org.studieo-javry:shared-internal-auth:0.0.1-SNAPSHOT")

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

tasks.withType<Test> {
  useJUnitPlatform()
}

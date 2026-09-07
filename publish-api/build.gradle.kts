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
description = "publish-api"

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
  implementation("io.github.oshai:kotlin-logging-jvm:7.0.7")

  // Kafka producer (outbox → relayer 가 KafkaTemplate 사용).
  implementation("org.springframework.kafka:spring-kafka")
  // ShedLock — OutboxRelayer / CleanupJob 의 단일 leader.
  implementation("net.javacrumbs.shedlock:shedlock-spring:6.10.0")
  implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.10.0")

  // gateway / core-api 가 발급한 internal JWT 검증 + core-api 호출용 발급
  implementation("org.studieo-javry:shared-internal-auth:0.0.1-SNAPSHOT")
  implementation("org.jetbrains.kotlin:kotlin-reflect")

  // PDF — server-side HTML → PDF 변환 (한글 폰트 임베드 가능)
  implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")
  implementation("com.openhtmltopdf:openhtmltopdf-slf4j:1.0.10")
  implementation("org.jsoup:jsoup:1.18.1")  // HTML → XHTML 정규화

  // Boot 4 → Jackson 3
  implementation("tools.jackson.module:jackson-module-kotlin")

  // AWS SDK v2 — 첨부 presigned URL 발급(core 와 동일 S3/MinIO 버킷). 공개 페이지 이미지 렌더용.
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

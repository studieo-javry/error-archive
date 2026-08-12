import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
  kotlin("jvm") version "2.3.20"
  kotlin("plugin.spring") version "2.3.20"
  id("org.springframework.boot") version "4.0.4"
  id("io.spring.dependency-management") version "1.1.7"
  `maven-publish`
}

group = "org.studieo-javry"
version = "0.0.1-SNAPSHOT"
description = "shared-error"

extra["springCloudVersion"] = "2025.1.1"

repositories { mavenCentral() }

dependencyManagement {
  imports {
    mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
  }
}

dependencies {
  // 소비 서비스가 starter 로 이미 가져오는 의존성 — compileOnly
  compileOnly("org.springframework.boot:spring-boot-autoconfigure")
  compileOnly("org.springframework.boot:spring-boot-starter-web")
  compileOnly("org.springframework.boot:spring-boot-starter-security")
  compileOnly("org.springframework.boot:spring-boot-starter-validation")
  compileOnly("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  compileOnly("jakarta.servlet:jakarta.servlet-api")
  compileOnly("tools.jackson.module:jackson-module-kotlin")

  implementation("io.micrometer:micrometer-tracing")
  implementation("io.github.oshai:kotlin-logging-jvm:7.0.7")
  implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
  implementation("org.jetbrains.kotlin:kotlin-reflect")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-starter-security")
  testImplementation("org.springframework.boot:spring-boot-starter-web")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { enabled = false }
tasks.named<Jar>("jar") { enabled = true }

configure<KotlinJvmProjectExtension> {
  jvmToolchain(25)
  compilerOptions {
    freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
  }
}

publishing {
  publications { create<MavenPublication>("maven") { from(components["java"]) } }
}

tasks.withType<Test> { useJUnitPlatform() }

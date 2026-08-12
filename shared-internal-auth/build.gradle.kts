import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// 내부 서비스 간 인증용 공유 라이브러리 (composite build 멤버).
// org.springframework.boot 플러그인은 형제 모듈과 의존성 관리를 맞추기 위해 적용하되,
// 실행 모듈이 아니므로 bootJar 를 끄고 일반 라이브러리 jar 를 생성한다.
// 소비 측(gateway/core-api/iam-api)이 GAV 좌표 "org.studieo-javry:shared-internal-auth"
// 로 의존하면 Gradle composite build 가 자동으로 로컬 빌드로 substitute 한다.
plugins {
  kotlin("jvm") version "2.3.20"
  kotlin("plugin.spring") version "2.3.20"
  id("org.springframework.boot") version "4.0.4"
  id("io.spring.dependency-management") version "1.1.7"
  // 이 라이브러리를 mavenLocal 등에 publish 가능하게 함
  `maven-publish`
}

// implementation("org.studieo-javry:shared-internal-auth:0.0.1-SNAPSHOT")
group = "org.studieo-javry"
version = "0.0.1-SNAPSHOT"
description = "shared-internal-auth"

repositories {
  mavenCentral()
}

dependencies {
  // 소비 측 앱이 starter 로 이미 가져오는 의존성 — 라이브러리는 compileOnly 로만 참조.
  // 컴파일할 때만 필요하고, 이 라이브러리 jar 안에는 포함하지 않겠다
  // shared-internal-auth: "나는 SecurityFilterChain, OncePerRequestFilter 같은 타입을 컴파일할 때만 알면 돼."
  // core-api: "실제로 실행될 때 Spring Security 의존성은 내가 가지고 있어."
  compileOnly("org.springframework.boot:spring-boot-autoconfigure")
  compileOnly("org.springframework.boot:spring-boot-starter-security")
  compileOnly("org.springframework.boot:spring-boot-starter-web")
  compileOnly("jakarta.servlet:jakarta.servlet-api")

  // JWT 서명/검증. Spring Security OAuth2 가 쓰는 버전과 동일하게 핀.
  implementation("com.nimbusds:nimbus-jose-jwt:10.4")

  implementation("io.github.oshai:kotlin-logging-jvm:7.0.7")
  implementation("org.jetbrains.kotlin:kotlin-reflect")

  // compileOnly 의존성은 test classpath 로 상속되지 않으므로 명시.
  testImplementation("jakarta.servlet:jakarta.servlet-api")
  testImplementation("org.springframework.boot:spring-boot-starter-security")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 라이브러리 모듈: 실행 가능한 bootJar 대신 일반 jar 를 산출물로 둔다.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
  enabled = false
}
tasks.named<Jar>("jar") {
  enabled = true
}

configure<KotlinJvmProjectExtension> {
  jvmToolchain(25)
  compilerOptions {
    freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
  }
}

// 일반 jar 를 라이브러리 아티팩트로 publish (mavenLocal). composite build 로 빌드할 땐
// substitution 이 우선하지만, 각 모듈을 단독 빌드할 때 GAV 좌표를 해소할 수 있게 한다.
publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
    }
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
}

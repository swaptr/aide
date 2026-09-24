// :server — AIDE backend (Spring Boot 4.1, Kotlin, Gradle Kotlin DSL, PostgreSQL).
//
// Generated from start.spring.io (`type=gradle-project-kotlin`, deps: web / actuator / validation /
// configuration-processor / devtools / data-jpa / postgresql / flyway / testcontainers / docker-compose) and
// then folded into this build: plugin versions moved to gradle/libs.versions.toml, and the generator's
// `repositories { mavenCentral() }` deleted because the root settings.gradle.kts sets
// `RepositoriesMode.FAIL_ON_PROJECT_REPOS` (repos are declared once, centrally).
//
// JVM-only, shares nothing with :app / :ui / :desktopApp yet — it lives in the same build so
// `./gradlew :server:bootRun` works from the repo root and it can `implementation(project(":core:domain"))`
// (its desktop/jvm target) later for shared domain/wire models. Charter: see ARCHITECTURE.md §19.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// Maven coordinates: group is the org domain, the app/module nesting lives in the package
// (com.sabreware.aide.server) and in the artifact name — so the jar is aide-server-<version>.jar, not
// server-<version>.jar, even though the Gradle path stays the short `:server`.
group = "com.sabreware"
version = "0.0.1-SNAPSHOT"
description = "AIDE backend server"

base {
    archivesName = "aide-server"
}

// 21, independent of the Android/desktop modules. Boot 4.1 needs JDK 17+; the toolchain means the build
// keeps running on whatever JVM Gradle itself started with (17 here) and only :server compiles/runs on 21.
// Kotlin picks the same toolchain up from the `java` block — no separate jvmToolchain() call. 21 also gates
// `spring.threads.virtual.enabled=true` in application.properties.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Spring Data JPA → Hibernate 7 + HikariCP (the connection pool; no separate dependency needed).
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Flyway owns the schema (`db/migration/V*.sql`); Hibernate only validates against it. Flyway 10+ needs
    // the per-database module in addition to core, hence flyway-database-postgresql.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Jackson 3 coordinates (`tools.jackson`, not `com.fasterxml.jackson`) — Boot 4's default JSON stack.
    // Needed for Kotlin data classes / nullability / default args in request+response bodies.
    implementation("tools.jackson.module:jackson-module-kotlin")

    runtimeOnly("org.postgresql:postgresql")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    // Boot's Docker Compose support: `bootRun` starts compose.yaml's `postgres` service and injects its
    // JDBC url/credentials as the datasource, then stops it on shutdown. developmentOnly ⇒ absent from the
    // packaged jar, where SPRING_DATASOURCE_* env vars supply the connection instead.
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    // Tests run against a real Postgres container (never H2) — @ServiceConnection wires it in, see
    // TestcontainersConfiguration.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        // -Xjsr305=strict: honour Spring's @Nullable/@NonNull as Kotlin nullability instead of platform types.
        // -Xannotation-default-target=param-property: Kotlin 2.3's new constructor-property annotation
        // placement, so `@field:`-style prefixes aren't needed on validated request DTO properties.
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// kotlin-spring already opens @Component/@Configuration; this adds the JPA annotations so Hibernate can
// subclass entities for lazy proxies. (kotlin-jpa's noarg half needs no configuration.)
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

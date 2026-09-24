package com.sabreware.aide.server

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Real Postgres for tests — never H2. `@ServiceConnection` hands the container's JDBC url/credentials to
 * Boot's datasource auto-configuration, so Flyway migrations and Postgres-specific SQL are exercised as
 * shipped. Import it from any test that needs a database (see [ServerApplicationTests]); one container is
 * reused across every test class in the JVM because the context is cached.
 *
 * Keep the image tag in step with compose.yaml.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
}

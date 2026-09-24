package com.sabreware.aide.server

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/** Whole-context smoke test: also asserts Flyway migrates and Hibernate's schema validation passes. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ServerApplicationTests {

    @Test
    fun contextLoads() {
    }
}

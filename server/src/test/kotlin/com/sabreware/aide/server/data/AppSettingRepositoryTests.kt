package com.sabreware.aide.server.data

import com.sabreware.aide.server.TestcontainersConfiguration
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Round-trips a row through the real Postgres container, so a broken migration / entity mapping fails here
 * rather than at runtime. Full `@SpringBootTest` (not `@DataJpaTest`) so the datasource is the container's,
 * not a replaced test database.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class AppSettingRepositoryTests(@Autowired val repository: AppSettingRepository) {

    @Test
    fun `saves and reads a setting`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        repository.save(AppSetting(key = "greeting", value = "hello", updatedAt = now))

        val loaded = repository.findById("greeting").orElseThrow()

        assertEquals("hello", loaded.value)
        assertEquals(now, loaded.updatedAt)
    }
}

package com.sabreware.aide.server.data

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Server-side key/value settings row — the one table in V1__init.sql, here to prove the Flyway → JPA →
 * Postgres path end to end.
 *
 * Deliberately a plain `class` with `var`s, not a `data class`: Hibernate proxies subclass the entity
 * (kotlin-spring/allOpen makes that possible) and generated `equals`/`hashCode` over mutable, lazily
 * initialised properties misbehaves inside a persistence context. Defaults on every property let
 * kotlin-jpa's no-arg constructor stay usable.
 */
@Entity
@Table(name = "app_setting")
class AppSetting(
    @Id
    @Column(name = "setting_key", length = 128)
    var key: String = "",

    @Column(name = "setting_value", nullable = false, length = 1024)
    var value: String = "",

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)

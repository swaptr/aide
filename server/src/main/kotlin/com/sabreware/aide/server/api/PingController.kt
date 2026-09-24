package com.sabreware.aide.server.api

import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Liveness probe for humans — `curl localhost:8080/api/ping`. Machine probes use `/actuator/health`. */
@RestController
@RequestMapping("/api")
class PingController {

    @GetMapping("/ping")
    fun ping(): Pong = Pong(status = "ok", at = Instant.now())
}

data class Pong(val status: String, val at: Instant)

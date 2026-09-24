package com.sabreware.aide.server

import org.springframework.boot.fromApplication
import org.springframework.boot.with

/**
 * Dev entry point that boots the app against the Testcontainers Postgres instead of compose.yaml's.
 * Run this main() from the IDE when you want a throwaway database per run.
 */
fun main(args: Array<String>) {
    fromApplication<ServerApplication>().with(TestcontainersConfiguration::class).run(*args)
}

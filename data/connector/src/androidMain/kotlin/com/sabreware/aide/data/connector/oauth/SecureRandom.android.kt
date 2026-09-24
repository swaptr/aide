package com.sabreware.aide.data.connector.oauth

import java.security.SecureRandom

private val random = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { random.nextBytes(it) }

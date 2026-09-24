package com.sabreware.aide.data.connector.oauth

/**
 * Fills [size] bytes from the platform CSPRNG. Backs PKCE verifier / `state` generation, which MUST be
 * cryptographically secure (RFC 7636). Android + desktop both delegate to `java.security.SecureRandom`; an
 * iOS actual (`SecRandomCopyBytes`) slots in here when the target lands.
 */
expect fun secureRandomBytes(size: Int): ByteArray

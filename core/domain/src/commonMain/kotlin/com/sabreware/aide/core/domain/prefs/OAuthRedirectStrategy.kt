package com.sabreware.aide.core.domain.prefs

/**
 * How the MCP OAuth login returns to the app after the user signs in inside the Custom Tab. User-selectable
 * in Settings → Connectors.
 *
 * - [LOOPBACK]: redirect to `http://127.0.0.1:<port>/cb`, caught by a transient in-app server socket. The
 *   MCP-ecosystem default (Claude Desktop, Cursor, Cline) and spec-compliant (localhost). Default.
 * - [CUSTOM_SCHEME]: redirect to `com.sabreware.aide://oauth-callback`, caught by OAuthCallbackActivity.
 *   Simplest Android UX, but some strict authorization servers reject non-localhost/https redirects.
 */
enum class OAuthRedirectStrategy { LOOPBACK, CUSTOM_SCHEME }

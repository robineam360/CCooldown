package com.robin.claudeusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins [ApiClient.normalizeProbePath], the guard on the CCBG-6 endpoint probe.
 *
 * The probe carries a live bearer token, so the point of these tests is the *rejections*:
 * any path that could retarget the request at another host must throw rather than be
 * quietly cleaned up, because a silently-rewritten path would send the token somewhere we
 * never chose.
 */
class ProbePathTest {

    @Test
    fun `adds exactly one leading slash`() {
        assertEquals("/api/oauth/usage", ApiClient.normalizeProbePath("api/oauth/usage"))
        assertEquals("/api/oauth/usage", ApiClient.normalizeProbePath("/api/oauth/usage"))
        assertEquals("/api/bootstrap", ApiClient.normalizeProbePath("  /api/bootstrap  "))
    }

    @Test
    fun `keeps query strings and templated segments intact`() {
        assertEquals(
            "/api/organizations/abc-123/usage?full=true",
            ApiClient.normalizeProbePath("/api/organizations/abc-123/usage?full=true"),
        )
    }

    /**
     * The one that matters most. `//evil.example/x` is a protocol-relative URL: appended
     * to an origin it resolves to a *different host*, taking the Authorization header
     * with it. It must not be silently collapsed to a single slash.
     */
    @Test
    fun `rejects a protocol-relative path`() {
        assertThrows(IllegalArgumentException::class.java) {
            ApiClient.normalizeProbePath("//evil.example/x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ApiClient.normalizeProbePath("// evil.example")
        }
    }

    @Test
    fun `rejects a scheme, userinfo, backslash or traversal`() {
        listOf(
            "https://evil.example/x",
            "/api@evil.example/x",
            "\\evil.example",
            "/api/../../secret",
            "",
            "   ",
        ).forEach { bad ->
            assertThrows("expected rejection for '$bad'", IllegalArgumentException::class.java) {
                ApiClient.normalizeProbePath(bad)
            }
        }
    }

    /**
     * `api.claude.ai` — the APK's own base-URL constant — has no A record, so the live
     * origin is `claude.ai`. Pinned so nobody "corrects" it back to the constant.
     */
    @Test
    fun `probe hosts are an allowlist of the two we intend`() {
        assertEquals(
            listOf("https://api.anthropic.com", "https://claude.ai"),
            ApiClient.ProbeHost.entries.map { it.origin },
        )
    }
}

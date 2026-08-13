package com.robin.claudeusage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [allowedLinkUrl], the guard in front of every browser launch for
 * CCRM-26 (Quick Links) and sign-in alike.
 *
 * Every launch site passes a compile-time https constant today, so the point of
 * these tests is the *rejections*: a future refactor that routes a dynamic URL
 * through `openInBrowser` must not be able to launch an `intent://` or
 * `javascript:` payload — those get a silent no-op, not a browser.
 */
class QuickLinksTest {

    @Test
    fun `accepts plain web schemes`() {
        assertTrue(allowedLinkUrl("https://status.anthropic.com"))
        assertTrue(allowedLinkUrl("https://claude.ai/settings/usage"))
        assertTrue(allowedLinkUrl("http://example.com"))
    }

    @Test
    fun `scheme match is case-insensitive and tolerates padding`() {
        assertTrue(allowedLinkUrl("HTTPS://status.anthropic.com"))
        assertTrue(allowedLinkUrl("  https://status.anthropic.com  "))
    }

    @Test
    fun `rejects non-web schemes`() {
        assertFalse(allowedLinkUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(allowedLinkUrl("javascript:alert(1)"))
        assertFalse(allowedLinkUrl("file:///etc/hosts"))
        assertFalse(allowedLinkUrl("mailto:someone@example.com"))
    }

    @Test
    fun `rejects strings with no scheme at all`() {
        assertFalse(allowedLinkUrl(""))
        assertFalse(allowedLinkUrl("status.anthropic.com"))
        // Protocol-relative: resolves to a host we never chose if a base sneaks in.
        assertFalse(allowedLinkUrl("//evil.example/x"))
    }

    /**
     * A colon later in the string must not be read as a scheme separator —
     * "example.com/a:b" has no scheme, and "https://host/a:b" is still https.
     */
    @Test
    fun `only the leading scheme counts`() {
        assertFalse(allowedLinkUrl("example.com/path:8080"))
        assertTrue(allowedLinkUrl("https://claude.ai/a:b"))
    }
}

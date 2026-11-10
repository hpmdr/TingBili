package cn.debubu.tingbili.core.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUpdateGateTest {

    private val build = 1_000_000L
    private val day = 24 * 60 * 60 * 1000L

    // ── shouldFetch ───────────────────────────────────────────────

    @Test fun `debug build never fetches`() {
        assertFalse(
            AutoUpdateGate.shouldFetch(
                debuggable = true,
                autoCheckEnabled = true,
                nowMs = 0L,
                lastCheckMs = 0L,
            ),
        )
    }

    @Test fun `auto check off never fetches`() {
        assertFalse(
            AutoUpdateGate.shouldFetch(
                debuggable = false,
                autoCheckEnabled = false,
                nowMs = day,
                lastCheckMs = 0L,
            ),
        )
    }

    @Test fun `within interval does not fetch`() {
        assertFalse(
            AutoUpdateGate.shouldFetch(
                debuggable = false,
                autoCheckEnabled = true,
                nowMs = day,
                lastCheckMs = day - UpdateChecker.AUTO_CHECK_INTERVAL_MS + 1,
            ),
        )
    }

    @Test fun `at interval boundary fetches`() {
        assertTrue(
            AutoUpdateGate.shouldFetch(
                debuggable = false,
                autoCheckEnabled = true,
                nowMs = UpdateChecker.AUTO_CHECK_INTERVAL_MS,
                lastCheckMs = 0L,
            ),
        )
    }

    @Test fun `first launch fetches`() {
        assertTrue(
            AutoUpdateGate.shouldFetch(
                debuggable = false,
                autoCheckEnabled = true,
                nowMs = System.currentTimeMillis(),
                lastCheckMs = 0L,
            ),
        )
    }

    // ── shouldPrompt ──────────────────────────────────────────────

    @Test fun `same version does not prompt`() {
        assertFalse(
            AutoUpdateGate.shouldPrompt(
                skippedReleaseSec = 0L,
                localVersion = "0.2.12",
                remoteTag = "v0.2.12",
                remotePublishedSec = 1_000_300L,
                localBuildSec = build,
            ),
        )
    }

    @Test fun `skipped release does not prompt`() {
        assertFalse(
            AutoUpdateGate.shouldPrompt(
                skippedReleaseSec = 1_000_300L,
                localVersion = "0.2.11",
                remoteTag = "v0.2.12",
                remotePublishedSec = 1_000_300L,
                localBuildSec = build,
            ),
        )
    }

    @Test fun `newer version prompts`() {
        assertTrue(
            AutoUpdateGate.shouldPrompt(
                skippedReleaseSec = 0L,
                localVersion = "0.2.11",
                remoteTag = "v0.2.12",
                remotePublishedSec = 1_000_300L,
                localBuildSec = build,
            ),
        )
    }

    @Test fun `unparseable remote time does not prompt`() {
        assertFalse(
            AutoUpdateGate.shouldPrompt(
                skippedReleaseSec = 0L,
                localVersion = "0.2.11",
                remoteTag = "v0.2.12",
                remotePublishedSec = null,
                localBuildSec = build,
            ),
        )
    }
}

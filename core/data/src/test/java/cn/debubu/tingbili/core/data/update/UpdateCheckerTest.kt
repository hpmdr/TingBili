package cn.debubu.tingbili.core.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test fun `parses Zulu time`() {
        assertEquals(1_700_000_000L, UpdateChecker.publishedAtSec("2023-11-14T22:13:20Z"))
    }

    @Test fun `parses offset time`() {
        assertEquals(1_700_000_000L, UpdateChecker.publishedAtSec("2023-11-15T06:13:20+08:00"))
    }

    @Test fun `bad input is null`() {
        assertNull(UpdateChecker.publishedAtSec(""))
        assertNull(UpdateChecker.publishedAtSec("v1.2.3"))
        assertNull(UpdateChecker.publishedAtSec("2023-13-99T99:99:99Z"))
    }

    @Test fun `picks apk and skips sha256`() {
        val assets = listOf(
            GitHubAsset("TingBili_1.1.0_android.apk.sha256", "https://x/y.sha256"),
            GitHubAsset("TingBili_1.1.0_android.apk", "https://x/y.apk"),
        )
        assertEquals("https://x/y.apk", UpdateChecker.pickApkUrl(assets))
    }

    @Test fun `no apk returns null`() {
        assertNull(UpdateChecker.pickApkUrl(emptyList()))
        assertNull(
            UpdateChecker.pickApkUrl(
                listOf(GitHubAsset("notes.txt", "https://x/notes.txt"))
            )
        )
    }
}

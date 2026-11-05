package cn.debubu.tingbili.core.data.update

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubUpdateDataSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: GitHubUpdateDataSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = GitHubUpdateDataSource()
        source.endpoint = server.url("/repos/x/y/releases/latest").toString()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test fun `parses latest release`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"tag_name":"v1.1.0","body":"- 修复xx","published_at":"2026-11-04T12:00:00Z","assets":[{"name":"TingBili_1.1.0_android.apk","browser_download_url":"https://github.com/x/y.apk"},{"name":"TingBili_1.1.0_android.apk.sha256","browser_download_url":"https://github.com/x/y.apk.sha256"}]}"""
                ),
        )
        val r = source.fetchLatest()
        assertTrue(r.isSuccess)
        val rel = r.getOrThrow()
        assertEquals("v1.1.0", rel.tag)
        assertEquals(2, rel.assets.size)
        assertEquals("https://github.com/x/y.apk", UpdateChecker.pickApkUrl(rel.assets))
        val recorded = server.takeRequest()
        assertEquals("TingBili", recorded.getHeader("User-Agent"))
    }

    @Test fun `http error is failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(source.fetchLatest().isFailure)
    }

    @Test fun `malformed json is failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        assertTrue(source.fetchLatest().isFailure)
    }
}

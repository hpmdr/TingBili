package cn.debubu.tingbili.core.media

import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackConnectionTest {

    @Test
    fun `session token points at playback service`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connection = PlaybackConnection(context)
        val token = connection.sessionToken
        assertTrue(token.serviceName.endsWith("TingBiliPlaybackService"))
        connection.release()
    }

    @Test
    fun `listener fan-out keeps listeners until release`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connection = PlaybackConnection(context)
        connection.addListener(object : Player.Listener {})
        assertTrue(connection.pendingListenerCountForTest() == 1)
        connection.release()
        assertNotNull(connection)
    }
}

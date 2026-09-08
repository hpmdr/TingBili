package cn.debubu.tingbili.core.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaControllerHandleTest {

    @Test
    fun `disconnected handle degrades to safe defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val handle: PlayerHandle = MediaControllerHandle(PlaybackConnection(context))
        assertEquals(0L, handle.currentPosition)
        assertEquals(0L, handle.duration)
        assertFalse(handle.isPlaying)
        // 未连接时控制命令为 no-op，不抛异常
        handle.play()
        handle.pause()
        handle.seekTo(1000L)
        handle.release() // Service 唯一释放，此处必须为空实现
    }
}

package cn.debubu.tingbili.core.data

import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/** 缓存上限派生：图片盘 = 音频上限 1/4，无封顶。 */
class CacheLimitsTest {

    @Test fun `image limit is quarter of audio limit`() {
        assertEquals(125, PreferencesRepository.imageCacheMaxMb(500))
        assertEquals(250, PreferencesRepository.imageCacheMaxMb(1000))
    }

    @Test fun `image limit is quarter without cap`() {
        assertEquals(256, PreferencesRepository.imageCacheMaxMb(1024))
        assertEquals(512, PreferencesRepository.imageCacheMaxMb(2048))
        assertEquals(768, PreferencesRepository.imageCacheMaxMb(3072))
        assertEquals(1024, PreferencesRepository.imageCacheMaxMb(4096))
        assertEquals(25000, PreferencesRepository.imageCacheMaxMb(100000))
    }

    @Test fun `image limit floors small values`() {
        assertEquals(25, PreferencesRepository.imageCacheMaxMb(100))
        assertEquals(0, PreferencesRepository.imageCacheMaxMb(0))
    }
}
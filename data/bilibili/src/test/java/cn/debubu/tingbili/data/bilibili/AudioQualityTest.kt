package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.dto.AudioQuality
import cn.debubu.tingbili.data.bilibili.dto.DashAudio
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlData
import cn.debubu.tingbili.data.bilibili.dto.DashData
import cn.debubu.tingbili.data.bilibili.dto.pickAudio
import cn.debubu.tingbili.data.bilibili.dto.selectAudio
import cn.debubu.tingbili.data.bilibili.dto.toAudioOptions
import cn.debubu.tingbili.data.bilibili.dto.toAudioUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 音质档位单测：rank 排序 / PiliPlus 同款降级语义 / 登录门槛标记。 */
class AudioQualityTest {

    private fun audio(id: Int, bw: Int, url: String = "https://cdn.example.com/$id.m4s") =
        DashAudio(baseUrl = url, id = id, bandwidth = bw)

    private fun dto(vararg tracks: DashAudio) =
        PlayUrlDto(code = 0, data = PlayUrlData(dash = DashData(audio = tracks.toList())))

    @Test
    fun `rank order matches PiliPlus`() {
        assertEquals(20, AudioQuality.fromCode(30216).rank)
        assertEquals(30, AudioQuality.fromCode(30232).rank)
        assertEquals(40, AudioQuality.fromCode(30280).rank)
        assertEquals(50, AudioQuality.fromCode(30250).rank)
        assertEquals(60, AudioQuality.fromCode(30251).rank)
    }

    @Test
    fun `unknown code falls to rank zero`() {
        assertEquals(0, AudioQuality.fromCode(99999).rank)
    }

    @Test
    fun `high tiers require login`() {
        assertEquals(false, AudioQuality.fromCode(30216).requiresLogin)
        assertEquals(false, AudioQuality.fromCode(30232).requiresLogin)
        assertEquals(true, AudioQuality.fromCode(30280).requiresLogin)
        assertEquals(true, AudioQuality.fromCode(30251).requiresLogin)
        assertEquals(true, AudioQuality.fromCode(30251).vipOnly)
    }

    @Test
    fun `default prefer picks 132K over 64K`() {
        val tracks = listOf(audio(30216, 64000), audio(30232, 132000))
        assertEquals(30232, tracks.pickAudio(AudioQuality.DEFAULT_CODE)?.id)
        assertEquals(30232, dto(audio(30216, 64000), audio(30232, 132000)).selectAudio()?.id)
    }

    @Test
    fun `hiRes prefer picks highest available`() {
        val tracks = listOf(audio(30216, 64000), audio(30232, 132000), audio(30280, 192000))
        assertEquals(30280, tracks.pickAudio(30251)?.id)
    }

    @Test
    fun `prefer below all falls back to lowest instead of silence`() {
        val tracks = listOf(audio(30232, 132000), audio(30280, 192000))
        assertEquals(30232, tracks.pickAudio(30216)?.id)
    }

    @Test
    fun `rank tie prefers higher bandwidth`() {
        val tracks = listOf(audio(30250, 200000), audio(30255, 250000))
        assertEquals(30255, tracks.pickAudio(30251)?.id)
    }

    @Test
    fun `empty list returns null`() {
        assertNull(emptyList<DashAudio>().pickAudio(30251))
        assertNull(dto().selectAudio())
        assertNull(dto().toAudioUrl())
    }

    @Test
    fun `blank urls are filtered before picking`() {
        val d = dto(DashAudio(baseUrl = "", id = 30232, bandwidth = 132000))
        assertEquals(0, d.toAudioOptions().size)
        assertNull(d.selectAudio())
    }

    @Test
    fun `toAudioUrl falls back to durl when no dash`() {
        val d = PlayUrlDto(
            code = 0,
            data = PlayUrlData(
                durl = listOf(cn.debubu.tingbili.data.bilibili.dto.DurlItem(url = "https://cdn.example.com/x.mp3"))
            )
        )
        assertEquals("https://cdn.example.com/x.mp3", d.toAudioUrl())
    }
}

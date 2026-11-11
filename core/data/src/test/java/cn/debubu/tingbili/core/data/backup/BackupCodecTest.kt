package cn.debubu.tingbili.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    @Test
    fun `round trip keeps all sections and auth`() {
        val payload = BackupPayload(
            appVersion = "0.3.0",
            exportedAt = 123L,
            includesAuth = true,
            data = BackupData(
                settings = SettingsBackup(
                    stepSec = 30,
                    audioQuality = 30280,
                    homeKeywords = listOf("悬疑", "有声书"),
                    searchHistory = listOf("盗墓"),
                ),
                folders = listOf(FolderBackup(1, "我的最爱", isDefault = true)),
                tracks = listOf(TrackBackup(1, "BV1", 1001, "曲目")),
                bvFavorites = listOf(BvFavoriteBackup("BV1", "合集")),
                bvPages = listOf(BvPageBackup("BV1", 1001, 1, "P1")),
                history = listOf(HistoryBackup("BV1", 1001, 5000, 1000, 3000, 0, "BV1")),
                auth = AuthBackup("SESS", "JCT", 42, "user", extras = mapOf("buvid3" to "b3")),
            ),
        )

        val decoded = BackupCodec.decode(BackupCodec.encode(payload)).getOrThrow()
        assertEquals(payload, decoded)
    }

    @Test
    fun `rejects wrong format marker`() {
        val result = BackupCodec.decode("""{"format":"other","formatVersion":1,"data":{}}""")
        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects newer format version`() {
        val raw = """{"format":"tingbili-backup","formatVersion":99,"data":{}}"""
        val result = BackupCodec.decode(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("更高版本"))
    }

    @Test
    fun `ignores unknown fields for forward compatibility`() {
        val raw = """
            {
              "format": "tingbili-backup",
              "formatVersion": 1,
              "futureField": {"a": 1},
              "data": {
                "settings": {"stepSec": 20, "futureSetting": true},
                "folders": []
              }
            }
        """.trimIndent()
        val decoded = BackupCodec.decode(raw).getOrThrow()
        assertEquals(20, decoded.data.settings.stepSec)
    }
}

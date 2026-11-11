package cn.debubu.tingbili.core.data.backup

import kotlinx.serialization.json.Json

/** 备份文件编解码：只做格式与版本校验，不碰数据库。 */
object BackupCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(payload: BackupPayload): String = json.encodeToString(BackupPayload.serializer(), payload)

    fun decode(raw: String): Result<BackupPayload> = try {
        val payload = json.decodeFromString(BackupPayload.serializer(), raw)
        when {
            payload.format != BACKUP_FORMAT ->
                Result.failure(IllegalArgumentException("不是有效的备份文件"))
            payload.formatVersion > BACKUP_FORMAT_VERSION ->
                Result.failure(IllegalArgumentException("备份由更高版本 App 导出，请先升级"))
            payload.formatVersion < 1 ->
                Result.failure(IllegalArgumentException("备份格式版本无效"))
            else -> Result.success(payload)
        }
    } catch (e: Exception) {
        Result.failure(IllegalArgumentException("备份文件解析失败，可能已损坏", e))
    }
}

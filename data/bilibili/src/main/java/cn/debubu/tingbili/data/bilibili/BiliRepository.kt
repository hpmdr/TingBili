package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.dto.toAudioUrl
import cn.debubu.tingbili.data.bilibili.dto.toLyricLines
import cn.debubu.tingbili.data.bilibili.dto.toTracks
import cn.debubu.tingbili.data.bilibili.model.LyricLine
import javax.inject.Inject

class BiliRepository @Inject constructor(
    private val api: BiliApi
) {

    suspend fun search(keyword: String): Result<List<Track>> = try {
        val dto = api.search(keyword)
        if (dto.code != 0) {
            Result.Error(dto.message.ifBlank { "search failed: code ${dto.code}" })
        } else {
            Result.Success(dto.toTracks())
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "search failed", e)
    }

    suspend fun getView(bvid: String): Result<List<Track>> = try {
        val dto = api.view(bvid)
        if (dto.code != 0) {
            Result.Error(dto.message.ifBlank { "view failed: code ${dto.code}" })
        } else {
            val tracks = dto.toTracks()
            Result.Success(tracks)
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "view failed", e)
    }

    suspend fun getPlayUrl(bvid: String, cid: Long): Result<String> = try {
        val dto = api.playUrl(bvid, cid)
        if (dto.code != 0) {
            Result.Error(dto.message.ifBlank { "playurl failed: code ${dto.code}" })
        } else {
            val url = dto.toAudioUrl()
            if (url.isNullOrBlank()) {
                Result.Error("no play url")
            } else {
                Result.Success(url)
            }
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "playurl failed", e)
    }

    suspend fun getSubtitle(bvid: String, cid: Long): Result<List<LyricLine>> = try {
        val dto = api.subtitle(bvid, cid)
        if (dto.code != 0) {
            Result.Error(dto.message.ifBlank { "subtitle failed: code ${dto.code}" })
        } else {
            Result.Success(dto.toLyricLines())
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "subtitle failed", e)
    }
}

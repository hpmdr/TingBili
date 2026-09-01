package cn.debubu.tingbili.core.data.model

data class Playlist(
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

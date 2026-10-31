package cn.debubu.tingbili.core.media

/** 当前播放列表的播放顺序（只属于这条队列，不是全局偏好）。 */
enum class PlayOrder(val code: Int) {
    FORWARD(0),
    REVERSE(1),
    ;

    companion object {
        fun fromCode(code: Int): PlayOrder =
            entries.firstOrNull { it.code == code } ?: FORWARD
    }
}

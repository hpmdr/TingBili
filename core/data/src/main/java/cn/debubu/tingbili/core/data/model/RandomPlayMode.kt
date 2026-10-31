package cn.debubu.tingbili.core.data.model

/**
 * 随机播放方式（播放设置里的全局偏好）。
 *
 * - [SHUFFLE]：洗牌，把当前列表按随机顺序打散，每轮每集最多播一次；
 * - [PURE_RANDOM]：纯随机，每次下一首都从当前列表随机取，可能重复播同一集。
 *
 * 注意：这只是「方式」，开关本身属于当前播放列表，不全局常开。
 */
enum class RandomPlayMode(val code: Int) {
    SHUFFLE(0),
    PURE_RANDOM(1),
    ;

    companion object {
        fun fromCode(code: Int): RandomPlayMode =
            entries.firstOrNull { it.code == code } ?: SHUFFLE
    }
}

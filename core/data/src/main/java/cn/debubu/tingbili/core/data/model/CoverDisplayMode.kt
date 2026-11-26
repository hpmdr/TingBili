package cn.debubu.tingbili.core.data.model

enum class CoverDisplayMode {
    BV_COVER,
    FIRST_FRAME,
    ;

    companion object {
        fun fromKey(key: String?): CoverDisplayMode = entries.firstOrNull { it.name == key } ?: BV_COVER
    }
}

fun Track.playbackCover(
    mode: CoverDisplayMode,
    singlePartUsesBvCover: Boolean,
): String = if (mode == CoverDisplayMode.FIRST_FRAME && !(singlePartUsesBvCover && pageCount <= 1)) {
    firstFrame?.takeIf { it.isNotBlank() } ?: cover
} else {
    cover
}

package cn.debubu.tingbili.core.data.model

enum class ImageFormat(val suffix: String) {
    AVIF("avif"),
    WEBP("webp"),
    JPEG("jpg");

    companion object {
        fun fromKey(value: String?): ImageFormat =
            entries.firstOrNull { it.name == value } ?: AVIF
    }
}

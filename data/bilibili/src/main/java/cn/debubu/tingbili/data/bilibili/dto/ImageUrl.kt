package cn.debubu.tingbili.data.bilibili.dto

import cn.debubu.tingbili.core.data.model.ImageFormat

/**
 * B 站图片 CDN 支持的常用展示规格。尺寸不能超过源图，否则 CDN 可能直接返回原图。
 */
enum class BiliImageVariant(val width: Int, val height: Int) {
    ListThumb(480, 270),
    DetailHero(720, 405),
    SquareCover(480, 480),
    SmallSquare(128, 128),
    Avatar(96, 96),
}

fun String.normalizeBiliImageUrl(): String {
    val normalized = when {
        startsWith("//") -> "https:$this"
        startsWith("http://") -> replaceFirst("http://", "https://")
        else -> this
    }
    return normalized.substringBefore('@')
}

fun String.biliImage(
    variant: BiliImageVariant,
    format: String = "webp",
): String = biliImage(variant.width, variant.height, format)

fun String.biliImage(
    variant: BiliImageVariant,
    format: ImageFormat,
): String = biliImage(variant.width, variant.height, format.suffix)

fun String.biliImage(
    width: Int,
    height: Int,
    format: String = "webp",
    crop: String = "1c",
): String {
    val base = normalizeBiliImageUrl()
    if (base.isBlank()) return ""
    return "$base@${width}w_${height}h_${crop}.$format"
}

fun String.biliImage(
    width: Int,
    height: Int,
    format: ImageFormat,
    crop: String = "1c",
): String = biliImage(width, height, format.suffix, crop)

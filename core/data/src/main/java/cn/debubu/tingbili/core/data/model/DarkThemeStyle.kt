package cn.debubu.tingbili.core.data.model

/** 深色风格：只在深色生效时起作用（纯黑省电护眼 / 深灰层级柔和），默认深灰 */
enum class DarkThemeStyle(val key: String) {
    DARK_GRAY("dark_gray"),
    PURE_BLACK("pure_black");

    companion object {
        fun fromKey(key: String?): DarkThemeStyle =
            entries.firstOrNull { it.key == key } ?: DARK_GRAY
    }
}

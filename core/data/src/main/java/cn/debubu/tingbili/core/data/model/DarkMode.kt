package cn.debubu.tingbili.core.data.model

/** 深色模式偏好：独立于主题色（ThemeMode 管颜色，DarkMode 管明暗） */
enum class DarkMode(val key: String) {
    FOLLOW_SYSTEM("follow_system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): DarkMode =
            entries.firstOrNull { it.key == key } ?: FOLLOW_SYSTEM
    }
}

package cn.debubu.tingbili.core.data.model

enum class ThemeMode(val key: String) {
    DEFAULT("default"),
    SYSTEM("system"),
    CUSTOM("custom");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

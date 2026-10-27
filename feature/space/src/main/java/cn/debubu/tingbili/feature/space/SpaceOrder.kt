package cn.debubu.tingbili.feature.space

/** 投稿排序，apiValue 直接透传给 arc/search 的 order 参数（与 B 站空间页一致） */
enum class SpaceOrder(val apiValue: String, val label: String) {
    PUBDATE("pubdate", "最新发布"),
    CLICK("click", "最多播放"),
    STOW("stow", "最多收藏"),
}

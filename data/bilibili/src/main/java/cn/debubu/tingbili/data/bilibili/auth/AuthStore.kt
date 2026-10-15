package cn.debubu.tingbili.data.bilibili.auth

/** 登录态持久化抽象；实现必须加密存储（SESSDATA 等同长期密码）。 */
interface AuthStore {
    fun load(): BiliSession?
    fun save(session: BiliSession?)
}

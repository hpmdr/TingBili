package cn.debubu.tingbili.core.data.backup

/**
 * 备份读写出口。
 *
 * 接口放 core:data（feature 只依赖它），实现放 :app：
 * 备份要同时访问 Room、DataStore 和 data:bilibili 的登录仓储，只有 app 聚合层不产生反向依赖。
 */
interface BackupStore {
    suspend fun export(includeAuth: Boolean): BackupPayload

    suspend fun import(payload: BackupPayload, mode: ImportMode): ImportResult
}

package cn.debubu.tingbili.core.data.repository

import cn.debubu.tingbili.core.data.db.HomeSnapshotDao
import cn.debubu.tingbili.core.data.db.HomeSnapshotEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 首页首屏快照的读写出口（REVIEW_REPORT D-01）：feature 层不再直接注入 `HomeSnapshotDao`。
 * 快照决定冷启动第一屏，写侧要保证「编码后的 JSON 与关键词同源」，收口后只在这里维护。
 */
interface HomeSnapshotRepository {
    suspend fun get(): HomeSnapshotEntity?
    fun observe(): Flow<HomeSnapshotEntity?>
    suspend fun save(entity: HomeSnapshotEntity)
    suspend fun clear()
}

@Singleton
class DefaultHomeSnapshotRepository @Inject constructor(
    private val dao: HomeSnapshotDao,
) : HomeSnapshotRepository {

    override suspend fun get(): HomeSnapshotEntity? = dao.get()

    override fun observe(): Flow<HomeSnapshotEntity?> = dao.observe()

    override suspend fun save(entity: HomeSnapshotEntity) = dao.save(entity)

    override suspend fun clear() = dao.clear()
}
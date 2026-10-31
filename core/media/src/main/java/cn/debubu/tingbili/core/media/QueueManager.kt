package cn.debubu.tingbili.core.media

import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.PlayQueueDao
import cn.debubu.tingbili.core.data.db.PlayQueueJson
import cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 队列唯一真相源：内存 queue/currentIndex + Room 单行快照 + 冷启动恢复。
 *
 * - transport（ExoPlayer playlist）只是渲染镜像：对不上时只更新 UI，不碰 transport；
 * - 状态同步走 [Listener.onQueueChanged]（同步回调，调用方合入 UI 态）；
 * - 持久化（快照/lastPosition）在自有 scope 内 launch，不阻塞调用方；
 * - 旧 DataStore 三键队列（drainLastQueue）一次性搬进 Room，读完即删键，之后只走 Room。
 */
class QueueManager(
    private val playQueue: PlayQueueDao,
    private val prefs: PreferencesRepository,
) {
    interface Listener {
        fun onQueueChanged(queue: List<Track>, index: Int, sourceTitle: String?)
    }

    /** 冷启动恢复结果：调用方原样合入 UI 态（暂停态，不自动播放）。 */
    data class Restored(
        val queue: List<Track>,
        val index: Int,
        val positionMs: Long,
        val speed: Float,
        val repeatMode: Int,
        val sourceTitle: String?,
        val sourceFolderId: Long?,
        val playOrder: PlayOrder,
        val shuffleEnabled: Boolean,
    )

    internal companion object {
        /** 队列快照上限（与旧 DataStore 版一致，控单行 JSON 大小） */
        const val MAX_QUEUE_SNAPSHOT = 100
    }

    var queue: List<Track> = emptyList()
        private set
    var currentIndex: Int = -1
        private set
    var sourceTitle: String? = null
        private set

    /** 队列来源收藏夹 id；PlayerManager 的 listener 直接读它同步到 UI 态。 */
    var sourceFolderId: Long? = null
        private set

    /** 当前播放列表的播放顺序（正序/倒序），只属于这条队列。 */
    var playOrder: PlayOrder = PlayOrder.FORWARD
        private set

    /** 当前播放列表的随机开关；随机方式在播放设置里选。 */
    var shuffleEnabled: Boolean = false
        private set

    private var scope: CoroutineScope? = null
    private var listener: Listener? = null

    fun attach(scope: CoroutineScope, listener: Listener) {
        this.scope = scope
        this.listener = listener
    }

    private fun notifyChanged() {
        listener?.onQueueChanged(queue, currentIndex, sourceTitle)
    }

    /**
     * 只更新来源（不动队列/不通知）：开播切队列时先用新来源盖住旧来源，
     * 避免解析 cid / 读图片格式的挂起窗口里历史 tick 把新曲写成旧收藏夹。
     */
    fun setSource(sourceTitle: String?, sourceFolderId: Long?) {
        this.sourceTitle = sourceTitle?.takeIf { it.isNotBlank() }
        this.sourceFolderId = sourceFolderId
    }

    /** 开播/切集确立新队列：设内存态 + 同步 UI + 持久化快照（调用方另行落 startPosition）。 */
    fun setActiveQueue(
        tracks: List<Track>,
        index: Int,
        sourceTitle: String?,
        sourceFolderId: Long? = null,
        playOrder: PlayOrder = PlayOrder.FORWARD,
        shuffleEnabled: Boolean = false,
    ) {
        queue = tracks
        currentIndex = index
        this.sourceTitle = sourceTitle?.takeIf { it.isNotBlank() }
        this.sourceFolderId = sourceFolderId
        this.playOrder = playOrder
        this.shuffleEnabled = shuffleEnabled
        notifyChanged()
        persistSnapshot()
    }

    /** 无有效队列时收下新列表等用户点（本来也没在播，不碰 transport，不落快照）。 */
    fun adoptTracks(tracks: List<Track>, sourceTitle: String?, sourceFolderId: Long? = null) {
        queue = tracks
        currentIndex = -1
        this.sourceTitle = sourceTitle?.takeIf { it.isNotBlank() }
        this.sourceFolderId = sourceFolderId
        notifyChanged()
    }

    /** 刷新对齐后的队列应用（不断播）：设内存态 + 同步 UI + 持久化快照。 */
    fun applyRefresh(
        final: List<Track>,
        playingFinalIdx: Int,
        sourceTitle: String?,
        sourceFolderId: Long? = null,
    ) {
        queue = final
        currentIndex = playingFinalIdx
        this.sourceTitle = sourceTitle?.takeIf { it.isNotBlank() }
        this.sourceFolderId = sourceFolderId
        notifyChanged()
        persistSnapshot()
    }

    /** 切歌跟进（transport transition）：同步下标 + UI + 持久化快照。 */
    fun moveCurrentTo(index: Int) {
        if (index !in queue.indices) return
        currentIndex = index
        notifyChanged()
        persistSnapshot()
    }

    /**
     * 切换当前播放列表的正/倒序：原地反转队列，当前在播项按 key 重新定位。
     * 只改这条队列，不碰收藏夹/BV。
     */
    fun setPlayOrder(order: PlayOrder) {
        if (order == playOrder) return
        val currentKey = queue.getOrNull(currentIndex)?.let { it.bvid to it.cid }
        queue = queue.reversed()
        currentIndex = if (currentKey == null) -1
        else queue.indexOfFirst { it.bvid == currentKey.first && it.cid == currentKey.second }
        playOrder = order
        notifyChanged()
        persistSnapshot()
    }

    /** 当前播放列表的随机开关（随机方式由播放设置决定）。 */
    fun setShuffleEnabled(enabled: Boolean) {
        if (enabled == shuffleEnabled) return
        shuffleEnabled = enabled
        notifyChanged()
        persistSnapshot()
    }

    /**
     * 来源收藏夹被清空/删除：清掉内存队列、来源与快照，UI 回到「未播放」。
     * 与 Media3 语义一致：队列没有可播项时进入 ended，而不是继续放已经删掉的条目。
     */
    fun clearQueue() {
        queue = emptyList()
        currentIndex = -1
        sourceTitle = null
        sourceFolderId = null
        playOrder = PlayOrder.FORWARD
        shuffleEnabled = false
        notifyChanged()
        scope?.launch {
            try {
                playQueue.clear()
            } catch (_: Exception) {
            }
        }
    }

    fun persistSnapshot() {
        val q = queue
        val idx = currentIndex
        val title = sourceTitle
        val folderId = sourceFolderId
        val order = playOrder
        val shuffle = shuffleEnabled
        if (q.isEmpty() || idx !in q.indices) return
        scope?.launch { saveSnapshot(q, idx, title, folderId, order, shuffle) }
    }

    private suspend fun saveSnapshot(
        tracks: List<Track>,
        index: Int,
        sourceTitle: String?,
        sourceFolderId: Long?,
        playOrder: PlayOrder,
        shuffleEnabled: Boolean,
    ) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        val capped = if (tracks.size > MAX_QUEUE_SNAPSHOT) tracks.take(MAX_QUEUE_SNAPSHOT) else tracks
        val snapshot = PlayQueueSnapshotEntity(
            tracksJson = PlayQueueJson.encode(capped),
            currentIndex = index.coerceIn(0, capped.lastIndex),
            sourceTitle = sourceTitle?.takeIf { it.isNotBlank() },
            sourceFolderId = sourceFolderId,
            playOrder = playOrder.code,
            shuffleEnabled = shuffleEnabled,
            updatedAt = System.currentTimeMillis(),
        )
        // 单行 Upsert 原子落盘：队列/下标/合集名要么一起新，要么一起旧
        try {
            playQueue.save(snapshot)
        } catch (_: Exception) {
        }
    }

    /** 恢复上次播放的队列与进度（暂停态）；损坏/空队列返回 null，调用方保持空状态。 */
    suspend fun restore(): Restored? {
        try {
            // Room 单行快照优先；空表时搬一次 DataStore 旧三键（搬完即删键），之后只走 Room
            var snapshot = try {
                playQueue.get()
            } catch (_: Exception) {
                null
            }
            if (snapshot == null) {
                val (legacyQueue, legacyIndex, legacyTitle) = try {
                    prefs.drainLastQueue()
                } catch (_: Exception) {
                    Triple(emptyList(), -1, null)
                }
                if (legacyQueue.isNotEmpty()) {
                    snapshot = PlayQueueSnapshotEntity(
                        tracksJson = PlayQueueJson.encode(legacyQueue.take(100)),
                        currentIndex = legacyIndex.coerceIn(0, legacyQueue.lastIndex),
                        sourceTitle = legacyTitle?.takeIf { it.isNotBlank() },
                        updatedAt = System.currentTimeMillis(),
                    )
                    try {
                        playQueue.save(snapshot)
                    } catch (_: Exception) {
                    }
                }
            }
            val savedQueue = snapshot?.let { PlayQueueJson.decode(it.tracksJson) }.orEmpty()
            if (savedQueue.isEmpty()) return null
            val savedIndex = (snapshot?.currentIndex ?: -1).coerceIn(0, savedQueue.lastIndex)
            val savedPos = try {
                prefs.lastPositionMs.first()
            } catch (_: Exception) {
                0L
            }
            val track = savedQueue.getOrNull(savedIndex) ?: return null
            queue = savedQueue
            currentIndex = savedIndex
            sourceTitle = snapshot?.sourceTitle
            sourceFolderId = snapshot?.sourceFolderId
            playOrder = PlayOrder.fromCode(snapshot?.playOrder ?: PlayOrder.FORWARD.code)
            shuffleEnabled = snapshot?.shuffleEnabled ?: false
            val speed = try {
                prefs.speed.first()
            } catch (_: Exception) {
                1f
            }
            val repeat = try {
                prefs.repeatMode.first()
            } catch (_: Exception) {
                0
            }
            val clampedPos = savedPos.coerceIn(0L, track.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
            return Restored(
                queue = savedQueue,
                index = savedIndex,
                positionMs = clampedPos,
                speed = speed,
                repeatMode = repeat,
                sourceTitle = sourceTitle,
                sourceFolderId = sourceFolderId,
                playOrder = playOrder,
                shuffleEnabled = shuffleEnabled,
            )
        } catch (_: Exception) {
            // 损坏数据忽略，保持空状态
            return null
        }
    }
}

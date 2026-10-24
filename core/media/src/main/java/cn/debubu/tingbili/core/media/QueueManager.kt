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

    private var scope: CoroutineScope? = null
    private var listener: Listener? = null

    fun attach(scope: CoroutineScope, listener: Listener) {
        this.scope = scope
        this.listener = listener
    }

    private fun notifyChanged() {
        listener?.onQueueChanged(queue, currentIndex, sourceTitle)
    }

    /** 开播/切集确立新队列：设内存态 + 同步 UI + 持久化快照（调用方另行落 startPosition）。 */
    fun setActiveQueue(tracks: List<Track>, index: Int, sourceTitle: String?) {
        queue = tracks
        currentIndex = index
        this.sourceTitle = sourceTitle?.takeIf { it.isNotBlank() }
        notifyChanged()
        persistSnapshot()
    }

    /** 无有效队列时收下新列表等用户点（本来也没在播，不碰 transport，不落快照）。 */
    fun adoptTracks(tracks: List<Track>, sourceTitle: String?) {
        queue = tracks
        currentIndex = -1
        this.sourceTitle = sourceTitle?.takeIf { it.isNotBlank() }
        notifyChanged()
    }

    /** 刷新对齐后的队列应用（不断播）：设内存态 + 同步 UI + 持久化快照。 */
    fun applyRefresh(final: List<Track>, playingFinalIdx: Int, sourceTitle: String?) {
        queue = final
        currentIndex = playingFinalIdx
        this.sourceTitle = sourceTitle?.takeIf { it.isNotBlank() }
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

    fun persistSnapshot() {
        val q = queue
        val idx = currentIndex
        val title = sourceTitle
        if (q.isEmpty() || idx !in q.indices) return
        scope?.launch { saveSnapshot(q, idx, title) }
    }

    private suspend fun saveSnapshot(tracks: List<Track>, index: Int, sourceTitle: String?) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        val capped = if (tracks.size > MAX_QUEUE_SNAPSHOT) tracks.take(MAX_QUEUE_SNAPSHOT) else tracks
        val snapshot = PlayQueueSnapshotEntity(
            tracksJson = PlayQueueJson.encode(capped),
            currentIndex = index.coerceIn(0, capped.lastIndex),
            sourceTitle = sourceTitle?.takeIf { it.isNotBlank() },
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
            )
        } catch (_: Exception) {
            // 损坏数据忽略，保持空状态
            return null
        }
    }
}

package cn.debubu.tingbili.data.bilibili.dto

/**
 * B 站音频档位（code 即 playurl dash.audio.id，也可作 qn 下发）。
 *
 * 对标 PiliPlus `AudioQuality`（lib/models/common/video/audio_quality.dart：30251 Hi-Res无损 /
 * 30250·30255 杜比全景声 / 30280 192K / 30232 132K / 30216 64K，rank 越大音质越高，
 * 选择语义为“最高 rank≤偏好，够不着则取最低”）。本应用照搬该 rank 降级语义（见 [pickAudio]）：
 * 未登录/无大会员时服务端只返回低档，选了高档也会诚实回落到可用最高档，不断播。
 */
class AudioQuality(
    val code: Int,
    val desc: String,
    val shortDesc: String,
    val rank: Int,
    /** 高档需登录（B 站只给登录用户返回 192K 及以上档位） */
    val requiresLogin: Boolean = false,
    /** 杜比/Hi-Res 另需大会员（无会员即使登录也拿不到，只作展示与提示用） */
    val vipOnly: Boolean = false,
) {
    companion object {
        val k64 = AudioQuality(30216, "流畅 64K", "64K", 20)
        val k132 = AudioQuality(30232, "标准 132K", "132K", 30)
        val k192 = AudioQuality(30280, "高品质 192K", "192K", 40, requiresLogin = true)
        val dolby = AudioQuality(30250, "杜比全景声", "杜比", 50, requiresLogin = true, vipOnly = true)
        val dolbyAlt = AudioQuality(30255, "杜比全景声", "杜比", 50, requiresLogin = true, vipOnly = true)
        val hiRes = AudioQuality(30251, "Hi-Res无损", "Hi-Res", 60, requiresLogin = true, vipOnly = true)

        /** 展示顺序：从高到低（PiliPlus values 同序；30255 与 30250 同为杜比只展示一行）。 */
        val values: List<AudioQuality> = listOf(hiRes, dolby, k192, k132, k64)

        /** 默认偏好：标准 132K——免登录可得的最佳档，不断播且不浪费流量。 */
        const val DEFAULT_CODE = 30232

        private val codeMap: Map<Int, AudioQuality> =
            (values + dolbyAlt).associateBy { it.code }

        fun isKnown(code: Int): Boolean = codeMap.containsKey(code)

        /** 未知 code 兜底 rank=0（最低），选择时自然垫底，不会误伤已知档位。 */
        fun fromCode(code: Int): AudioQuality =
            codeMap[code] ?: AudioQuality(code, "未知音质", code.toString(), 0)
    }
}

/**
 * PiliPlus `findTargetAudio` 同款选择语义（PiliPlus lib/utils/video_utils.dart +
 * lib/utils/extension/iterable_ext.dart）：取 rank 最高且 `rank <= 偏好 rank` 的一项；
 * 若偏好低于全列表（或偏好未知），取 rank 最低项兜底，保证永远有声。
 * rank 并列时取 bandwidth 更高者。
 */
fun List<DashAudio>.pickAudio(preferCode: Int): DashAudio? {
    if (isEmpty()) return null
    val preferRank = AudioQuality.fromCode(preferCode).rank
    var sel: DashAudio? = null
    var selRank = -1
    var selBw = -1
    var low: DashAudio? = null
    var lowRank = Int.MAX_VALUE
    var lowBw = -1
    for (a in this) {
        val r = AudioQuality.fromCode(a.id).rank
        if (r <= preferRank && (r > selRank || (r == selRank && a.bandwidth > selBw))) {
            sel = a
            selRank = r
            selBw = a.bandwidth
        }
        if (r < lowRank || (r == lowRank && a.bandwidth > lowBw)) {
            low = a
            lowRank = r
            lowBw = a.bandwidth
        }
    }
    return sel ?: low
}

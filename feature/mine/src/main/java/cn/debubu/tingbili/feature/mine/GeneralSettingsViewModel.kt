package cn.debubu.tingbili.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.CoverDisplayMode
import cn.debubu.tingbili.core.data.model.ImageFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「其他设置」：B 站图片格式、首页推荐词（版本更新已迁到「关于」页）。 */
@HiltViewModel
class GeneralSettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
) : ViewModel() {

    val imageFormat: StateFlow<ImageFormat> = prefs.imageFormat.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ImageFormat.AVIF
    )

    fun setImageFormat(v: ImageFormat) {
        viewModelScope.launch { prefs.setImageFormat(v) }
    }

    val coverDisplayMode: StateFlow<CoverDisplayMode> = prefs.coverDisplayMode.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), CoverDisplayMode.BV_COVER
    )

    val singlePartUsesBvCover: StateFlow<Boolean> = prefs.singlePartUsesBvCover.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    fun setCoverDisplayMode(mode: CoverDisplayMode) {
        viewModelScope.launch { prefs.setCoverDisplayMode(mode) }
    }

    fun setSinglePartUsesBvCover(enabled: Boolean) {
        viewModelScope.launch { prefs.setSinglePartUsesBvCover(enabled) }
    }

    /** 首页推荐词（设置页可增减，至少保留一个） */
    val homeKeywords: StateFlow<List<String>> = prefs.homeKeywords.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesRepository.DEFAULT_HOME_KEYWORDS
    )

    fun addHomeKeyword(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        viewModelScope.launch {
            val cur = homeKeywords.value
            if (k !in cur) prefs.setHomeKeywords(cur + k)
        }
    }

    fun removeHomeKeyword(keyword: String) {
        viewModelScope.launch {
            val cur = homeKeywords.value
            if (cur.size <= 1) return@launch // 至少保留一个
            prefs.setHomeKeywords(cur - keyword)
        }
    }

    /** 推荐词弹层使用草稿编辑，完成时一次性提交。 */
    fun setHomeKeywords(keywords: List<String>) {
        val cleaned = keywords
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .ifEmpty { listOf(PreferencesRepository.DEFAULT_HOME_KEYWORDS.first()) }
        viewModelScope.launch { prefs.setHomeKeywords(cleaned) }
    }
}

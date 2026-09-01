package cn.debubu.tingbili.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PreferencesRepository(private val ds: DataStore<Preferences>) {

    val stepSec: Flow<Int> = ds.data.map { it[STEP_SEC] ?: 15 }
    val repeatMode: Flow<Int> = ds.data.map { it[REPEAT_MODE] ?: 0 }
    val speed: Flow<Float> = ds.data.map { it[SPEED] ?: 1f }
    val timerPresets: Flow<Set<Int>> = ds.data.map { prefs ->
        prefs[TIMER_PRESETS]?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(15, 30, 60, 90)
    }

    val dynamicColor: Flow<Boolean> = ds.data.map { it[DYNAMIC_COLOR] ?: true }

    suspend fun setStep(v: Int) {
        ds.edit { it[STEP_SEC] = v }
    }

    suspend fun setRepeatMode(v: Int) {
        ds.edit { it[REPEAT_MODE] = v }
    }

    suspend fun setSpeed(v: Float) {
        ds.edit { it[SPEED] = v }
    }

    suspend fun setTimerPresets(presets: Set<Int>) {
        ds.edit { it[TIMER_PRESETS] = presets.joinToString(",") }
    }

    suspend fun setDynamicColor(v: Boolean) {
        ds.edit { it[DYNAMIC_COLOR] = v }
    }

    companion object {
        val STEP_SEC = intPreferencesKey("step_sec")
        val REPEAT_MODE = intPreferencesKey("repeat_mode")
        val SPEED = floatPreferencesKey("speed")
        val TIMER_PRESETS = stringPreferencesKey("timer_presets")
        val DYNAMIC_COLOR = androidx.datastore.preferences.core.booleanPreferencesKey("dynamic_color")
    }
}

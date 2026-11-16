package com.rullino.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Quanto resta un rullino al laboratorio prima che i negativi siano pronti. */
enum class LabTime(val label: String, val millis: Long) {
    HOUR("1 ora", 60L * 60 * 1000),
    THREE_HOURS("3 ore", 3L * 60 * 60 * 1000),
    DAY("1 giorno", 24L * 60 * 60 * 1000),
}

/** Opzioni dell'app, salvate sul telefono. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("rullino-settings", Context.MODE_PRIVATE)

    private val _labTime = MutableStateFlow(
        runCatching { LabTime.valueOf(prefs.getString("labTime", LabTime.DAY.name)!!) }.getOrDefault(LabTime.DAY)
    )
    val labTime: StateFlow<LabTime> = _labTime

    private val _developerMode = MutableStateFlow(prefs.getBoolean("developerMode", false))
    val developerMode: StateFlow<Boolean> = _developerMode

    private val _skipLabWait = MutableStateFlow(prefs.getBoolean("skipLabWait", false))
    /** Solo in modalità sviluppatore: i negativi sono pronti subito. */
    val skipLabWait: StateFlow<Boolean> = _skipLabWait

    fun setLabTime(t: LabTime) { _labTime.value = t; prefs.edit().putString("labTime", t.name).apply() }

    fun setDeveloperMode(on: Boolean) {
        _developerMode.value = on
        prefs.edit().putBoolean("developerMode", on).apply()
        if (!on) setSkipLabWait(false)
    }

    fun setSkipLabWait(on: Boolean) { _skipLabWait.value = on; prefs.edit().putBoolean("skipLabWait", on).apply() }

    /** Attesa effettiva per un nuovo rullino. */
    fun currentWaitMillis(): Long = if (_developerMode.value && _skipLabWait.value) 0L else _labTime.value.millis

    /** Un rullino è pronto se è passato il tempo, o se in modalità sviluppatore si saltano le attese. */
    fun isReady(roll: RollEntity, now: Long = System.currentTimeMillis()): Boolean =
        (_developerMode.value && _skipLabWait.value) || (roll.readyAt ?: 0L) <= now
}

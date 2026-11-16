package com.rullino.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * Il negozio: gettoni (la valuta interna, guadagnabile in futuro con i concorsi)
 * e il rullino gratis da ritirare ogni giorno.
 */
class Shop(context: Context) {
    private val prefs = context.getSharedPreferences("rullino-shop", Context.MODE_PRIVATE)

    companion object {
        /** Prezzo di un rullino in gettoni. */
        const val ROLL_PRICE = 10
    }

    private val _coins = MutableStateFlow(prefs.getInt("coins", 0))
    val coins: StateFlow<Int> = _coins

    private val _lastDaily = MutableStateFlow(prefs.getString("lastDaily", "") ?: "")
    val lastDaily: StateFlow<String> = _lastDaily

    fun today(): String = LocalDate.now().toString()
    fun dailyAvailable(): Boolean = _lastDaily.value != today()

    /** L'emulsione del giorno: ruota ogni giorno tra quelle installate. */
    fun dailyEmulsion(installed: List<EmulsionEntity>): EmulsionEntity? {
        if (installed.isEmpty()) return null
        val sorted = installed.sortedBy { it.id }
        val day = LocalDate.now().toEpochDay()
        return sorted[(day % sorted.size).toInt()]
    }

    fun markDailyClaimed() { _lastDaily.value = today(); prefs.edit().putString("lastDaily", today()).apply() }
    fun resetDaily() { _lastDaily.value = ""; prefs.edit().putString("lastDaily", "").apply() }

    /** Toglie gettoni; false se non bastano. */
    fun spend(n: Int): Boolean {
        if (_coins.value < n) return false
        setCoins(_coins.value - n)
        return true
    }

    fun earn(n: Int) = setCoins(_coins.value + n)

    private fun setCoins(v: Int) { _coins.value = v; prefs.edit().putInt("coins", v).apply() }
}

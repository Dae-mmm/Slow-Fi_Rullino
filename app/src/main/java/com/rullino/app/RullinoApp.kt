package com.rullino.app

import com.rullino.app.data.Account
import android.app.Application
import android.util.Log
import com.rullino.app.data.AppDb
import com.rullino.app.data.Repository
import com.rullino.app.data.Settings
import com.rullino.app.data.Shop
import com.rullino.app.develop.Developer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RullinoApp : Application() {
    /** Per il lavoro che deve sopravvivere alle schermate (sviluppo, download). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repo: Repository
        private set
    lateinit var developer: Developer
        private set
    lateinit var settings: Settings
        private set
    lateinit var shop: Shop
        private set
    lateinit var account: Account
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDb.build(this)
        repo = Repository(this, db)
        settings = Settings(this)
        shop = Shop(this)
        account = Account(this, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
        developer = Developer(this, repo, settings)
        appScope.launch { runCatching { account.refresh() } }
        appScope.launch {
            cleanOrphanRolls(db)
            installBundledEmulsions()
            // Riprende uno sviluppo interrotto (app chiusa a metà).
            for (roll in db.rolls().developing()) {
                runCatching { developer.develop(roll.id) }.onFailure { Log.e("Rullino", "Sviluppo ripreso fallito", it) }
            }
        }
    }

    /**
     * Toglie le cartelle dei rullini che il database non conosce più
     * (per esempio dopo un aggiornamento che ha azzerato i dati).
     */
    private suspend fun cleanOrphanRolls(db: AppDb) {
        val dir = java.io.File(filesDir, "rolls")
        val known = db.rolls().allIds().toSet()
        dir.listFiles()?.forEach { f ->
            val id = f.name.toLongOrNull()
            if (id == null || id !in known) f.deleteRecursively()
        }
    }

    /** Al primo avvio installa le emulsioni incluse nell'app (assets/emulsions). */
    private suspend fun installBundledEmulsions() {
        val prefs = getSharedPreferences("rullino", MODE_PRIVATE)
        val done = prefs.getInt("bundledVersion", 0)
        if (done >= BUNDLED_VERSION) return
        val files = assets.list("emulsions").orEmpty().filter { it.endsWith(".emulsion") }
        for (name in files) {
            runCatching { assets.open("emulsions/$name").use { repo.installer.install(it, "bundled") } }
                .onFailure { Log.e("Rullino", "Emulsione inclusa non valida: $name", it) }
        }
        prefs.edit().putInt("bundledVersion", BUNDLED_VERSION).apply()
    }

    private companion object {
        const val BUNDLED_VERSION = 4
    }
}

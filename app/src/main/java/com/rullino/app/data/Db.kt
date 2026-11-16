package com.rullino.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Un'emulsione installata: i file stanno in [dir]. */
@Entity(tableName = "emulsions")
data class EmulsionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val iso: Int,
    val exposures: Int,
    val type: String,
    val note: String,
    val dir: String,
    val boxFile: String?,
    val canisterFile: String?,
    /** Percorsi relativi delle foto demo, separati da '|'. */
    val demoFiles: String,
    val source: String,
    val version: Int,
    val installedAt: Long,
) {
    val isBw get() = type == "bw"
    fun demos(): List<String> = demoFiles.split('|').filter { it.isNotBlank() }
}

object RollState {
    /** In macchina, si può scattare. */
    const val LOADED = "LOADED"
    /** Sganciato a metà e messo da parte: si può ricaricare e finire. */
    const val SHELVED = "SHELVED"
    /** In macchina, finito o sganciato per lo sviluppo: aspetta di essere sviluppato. */
    const val FINISHED = "FINISHED"
    const val DEVELOPING = "DEVELOPING"
    /** Sviluppato: è una striscia di negativi sul tavolo luminoso. */
    const val DEVELOPED = "DEVELOPED"
}

/** Un rullino. */
@Entity(tableName = "rolls")
data class RollEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val emulsionId: String,
    val emulsionName: String,
    val iso: Int,
    val exposures: Int,
    /** Numero del prossimo fotogramma che verrà esposto (1…exposures+1). Riavvolgendo torna indietro. */
    val cursor: Int = 1,
    /** Fotogramma più avanzato mai esposto. */
    val shotCount: Int = 0,
    /** true quando la leva è stata caricata e l'otturatore è armato. */
    val wound: Boolean = false,
    val state: String = RollState.LOADED,
    val isBw: Boolean = false,
    val loadedAt: Long,
    val finishedAt: Long? = null,
    val developedAt: Long? = null,
) {
    val canWind get() = state == RollState.LOADED && !wound && cursor <= exposures
}

/** Una singola esposizione. Più esposizioni sullo stesso fotogramma fanno una doppia esposizione. */
@Entity(tableName = "exposures", indices = [Index("rollId")])
data class ExposureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rollId: Long,
    val frame: Int,
    val rawPath: String,
    val takenAt: Long,
)

/** Un fotogramma sviluppato: [developedPath] è il positivo privato, [printUri] la stampa in galleria. */
@Entity(tableName = "frames", primaryKeys = ["rollId", "frame"])
data class FrameEntity(
    val rollId: Long,
    val frame: Int,
    val developedPath: String,
    val exposureCount: Int = 1,
    val printUri: String? = null,
)

@Dao
interface EmulsionDao {
    @Query("SELECT * FROM emulsions ORDER BY installedAt DESC")
    fun all(): Flow<List<EmulsionEntity>>

    @Query("SELECT * FROM emulsions WHERE id = :id")
    suspend fun get(id: String): EmulsionEntity?

    @Upsert
    suspend fun upsert(e: EmulsionEntity)

    @Query("DELETE FROM emulsions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RollDao {
    /** Il rullino in macchina (caricato, finito o in sviluppo). */
    @Query("SELECT * FROM rolls WHERE state IN ('LOADED', 'FINISHED', 'DEVELOPING') ORDER BY loadedAt DESC LIMIT 1")
    fun active(): Flow<RollEntity?>

    @Query("SELECT * FROM rolls WHERE state IN ('LOADED', 'FINISHED', 'DEVELOPING')")
    suspend fun activeNow(): List<RollEntity>

    @Query("SELECT * FROM rolls ORDER BY loadedAt DESC")
    fun all(): Flow<List<RollEntity>>

    @Query("SELECT * FROM rolls WHERE id = :id")
    suspend fun get(id: Long): RollEntity?

    @Query("SELECT * FROM rolls WHERE state = 'DEVELOPING'")
    suspend fun developing(): List<RollEntity>

    @Query("SELECT COUNT(*) FROM rolls WHERE emulsionId = :emulsionId AND state != 'DEVELOPED'")
    suspend fun openWithEmulsion(emulsionId: String): Int

    @Insert
    suspend fun insert(r: RollEntity): Long

    @Update
    suspend fun update(r: RollEntity)

    @Delete
    suspend fun delete(r: RollEntity)
}

@Dao
interface ExposureDao {
    @Insert
    suspend fun insert(e: ExposureEntity): Long

    @Query("SELECT * FROM exposures WHERE rollId = :rollId ORDER BY frame, takenAt")
    suspend fun forRoll(rollId: Long): List<ExposureEntity>

    @Query("SELECT COUNT(*) FROM exposures WHERE rollId = :rollId AND frame = :frame")
    suspend fun countOnFrame(rollId: Long, frame: Int): Int

    @Query("SELECT COUNT(*) FROM exposures WHERE rollId = :rollId AND frame = :frame")
    fun countOnFrameFlow(rollId: Long, frame: Int): Flow<Int>

    @Query("DELETE FROM exposures WHERE rollId = :rollId")
    suspend fun deleteRoll(rollId: Long)

    @Query("DELETE FROM exposures WHERE rollId = :rollId AND frame = :frame")
    suspend fun deleteFrame(rollId: Long, frame: Int)
}

@Dao
interface FrameDao {
    @Upsert
    suspend fun upsert(f: FrameEntity)

    @Query("SELECT * FROM frames WHERE rollId = :rollId ORDER BY frame")
    suspend fun forRoll(rollId: Long): List<FrameEntity>

    @Query("SELECT * FROM frames WHERE rollId = :rollId ORDER BY frame")
    fun forRollFlow(rollId: Long): Flow<List<FrameEntity>>

    @Query("SELECT * FROM frames WHERE rollId = :rollId AND frame = :frame")
    suspend fun get(rollId: Long, frame: Int): FrameEntity?

    @Query("DELETE FROM frames WHERE rollId = :rollId")
    suspend fun deleteRoll(rollId: Long)

    @Query("DELETE FROM frames WHERE rollId = :rollId AND frame = :frame")
    suspend fun deleteFrame(rollId: Long, frame: Int)
}

@Database(
    entities = [EmulsionEntity::class, RollEntity::class, ExposureEntity::class, FrameEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun emulsions(): EmulsionDao
    abstract fun rolls(): RollDao
    abstract fun exposures(): ExposureDao
    abstract fun frames(): FrameDao

    companion object {
        fun build(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "rullino.db")
                // App ancora in sviluppo: cambiando schema si riparte da zero.
                .fallbackToDestructiveMigration(true)
                .build()
    }
}

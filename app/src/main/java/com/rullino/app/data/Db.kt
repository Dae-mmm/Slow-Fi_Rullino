package com.rullino.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import androidx.room.ColumnInfo
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
    /** Riavvolto e tolto dalla macchina: va portato al laboratorio. */
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
    /** Quando i negativi tornano dal laboratorio. */
    val readyAt: Long? = null,
    /** Negativi ritirati al negozio: ora sono sul tavolo luminoso. */
    val pickedUp: Boolean = false,
    /** Sviluppo chiesto al laboratorio: -1 pull, 0 normale, +1/+2 push. */
    @ColumnInfo(defaultValue = "0") val push: Int = 0,
    /** Rullino regalato da un concorso: id del concorso e codice del regalo sul server. */
    val contestId: String? = null,
    val contestRef: String? = null,
) {
    val canWind get() = state == RollState.LOADED && !wound && cursor <= exposures
    /** Tutte le pose esposte: va riavvolto. */
    val isAtEnd get() = state == RollState.LOADED && cursor > exposures
}

/** Rullini vergini in magazzino, per emulsione. */
@Entity(tableName = "inventory")
data class InventoryEntity(
    @PrimaryKey val emulsionId: String,
    val count: Int,
)

/** Liberatoria firmata per una posa. */
@Entity(tableName = "releases", indices = [Index("rollId")])
data class ReleaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rollId: Long,
    val frame: Int,
    val photographer: String,
    val firstName: String,
    val lastName: String,
    val contact: String,
    val place: String,
    val signedAt: Long,
    /** La persona autorizza anche usi fuori dall'app (mostre, stampe, pubblicazioni). */
    val externalUse: Boolean,
    val isMinor: Boolean,
    /** Genitore o tutore che firma per un minore. */
    val guardianName: String,
    val signaturePath: String,
)

/** Una singola esposizione. Più esposizioni sullo stesso fotogramma fanno una doppia esposizione. */
@Entity(tableName = "exposures", indices = [Index("rollId")])
data class ExposureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rollId: Long,
    val frame: Int,
    val rawPath: String,
    val takenAt: Long,
    /** Tempo scelto sulla ghiera, in secondi; null = automatico. */
    val shutter: Double? = null,
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
    /** Il rullino in macchina. */
    @Query("SELECT * FROM rolls WHERE state = 'LOADED' ORDER BY loadedAt DESC LIMIT 1")
    fun active(): Flow<RollEntity?>

    @Query("SELECT * FROM rolls WHERE state = 'LOADED'")
    suspend fun activeNow(): List<RollEntity>

    @Query("SELECT * FROM rolls ORDER BY loadedAt DESC")
    fun all(): Flow<List<RollEntity>>

    @Query("SELECT * FROM rolls WHERE id = :id")
    suspend fun get(id: Long): RollEntity?

    @Query("SELECT * FROM rolls")
    suspend fun allNow(): List<RollEntity>

    @Query("SELECT id FROM rolls")
    suspend fun allIds(): List<Long>

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
interface InventoryDao {
    @Query("SELECT * FROM inventory WHERE count > 0")
    fun all(): Flow<List<InventoryEntity>>

    @Query("SELECT * FROM inventory WHERE emulsionId = :id")
    suspend fun get(id: String): InventoryEntity?

    @Upsert
    suspend fun upsert(i: InventoryEntity)
}

@Dao
interface ReleaseDao {
    @Insert
    suspend fun insert(r: ReleaseEntity): Long

    @Query("SELECT * FROM releases WHERE rollId = :rollId ORDER BY frame, signedAt")
    fun forRollFlow(rollId: Long): Flow<List<ReleaseEntity>>

    @Query("SELECT * FROM releases WHERE rollId = :rollId")
    suspend fun forRoll(rollId: Long): List<ReleaseEntity>

    @Delete
    suspend fun delete(r: ReleaseEntity)

    @Query("DELETE FROM releases WHERE rollId = :rollId")
    suspend fun deleteRoll(rollId: Long)

    @Query("DELETE FROM releases WHERE rollId = :rollId AND frame = :frame")
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
    entities = [
        EmulsionEntity::class, RollEntity::class, ExposureEntity::class, FrameEntity::class,
        InventoryEntity::class, ReleaseEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun emulsions(): EmulsionDao
    abstract fun rolls(): RollDao
    abstract fun exposures(): ExposureDao
    abstract fun frames(): FrameDao
    abstract fun inventory(): InventoryDao
    abstract fun releases(): ReleaseDao

    companion object {
        /** Push/pull: si aggiunge la colonna senza perdere i rullini. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rolls ADD COLUMN push INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Ghiera dei tempi: ogni esposizione si ricorda il suo tempo. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exposures ADD COLUMN shutter REAL")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rolls ADD COLUMN contestId TEXT")
                db.execSQL("ALTER TABLE rolls ADD COLUMN contestRef TEXT")
            }
        }

        fun build(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "rullino.db")
                // App ancora in sviluppo: cambiando schema si riparte da zero.
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .fallbackToDestructiveMigration(true)
                .build()
    }
}

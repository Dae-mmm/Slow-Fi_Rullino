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
    const val LOADED = "LOADED"
    const val FINISHED = "FINISHED"
    const val DEVELOPING = "DEVELOPING"
    const val DEVELOPED = "DEVELOPED"
}

/** Un rullino caricato in macchina. */
@Entity(tableName = "rolls")
data class RollEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val emulsionId: String,
    val emulsionName: String,
    val iso: Int,
    val exposures: Int,
    val shotCount: Int = 0,
    /** true quando la leva è stata caricata e l'otturatore è armato. */
    val wound: Boolean = false,
    val state: String = RollState.LOADED,
    val loadedAt: Long,
    val finishedAt: Long? = null,
    val developedAt: Long? = null,
)

/** Uno scatto: [rawPath] è il "negativo" privato, [developedUri] la foto in galleria. */
@Entity(tableName = "shots", indices = [Index("rollId")])
data class ShotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rollId: Long,
    val frame: Int,
    val rawPath: String,
    val takenAt: Long,
    val developedUri: String? = null,
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
    @Query("SELECT * FROM rolls WHERE state != 'DEVELOPED' ORDER BY loadedAt DESC LIMIT 1")
    fun active(): Flow<RollEntity?>

    @Query("SELECT * FROM rolls ORDER BY loadedAt DESC")
    fun all(): Flow<List<RollEntity>>

    @Query("SELECT * FROM rolls WHERE id = :id")
    suspend fun get(id: Long): RollEntity?

    @Query("SELECT * FROM rolls WHERE state = 'DEVELOPING'")
    suspend fun developing(): List<RollEntity>

    @Query("SELECT * FROM rolls WHERE state != 'DEVELOPED'")
    suspend fun activeNow(): List<RollEntity>

    @Insert
    suspend fun insert(r: RollEntity): Long

    @Update
    suspend fun update(r: RollEntity)

    @Delete
    suspend fun delete(r: RollEntity)
}

@Dao
interface ShotDao {
    @Insert
    suspend fun insert(s: ShotEntity): Long

    @Update
    suspend fun update(s: ShotEntity)

    @Query("SELECT * FROM shots WHERE rollId = :rollId ORDER BY frame")
    suspend fun forRoll(rollId: Long): List<ShotEntity>

    @Query("SELECT * FROM shots WHERE rollId = :rollId ORDER BY frame")
    fun forRollFlow(rollId: Long): Flow<List<ShotEntity>>
}

@Database(entities = [EmulsionEntity::class, RollEntity::class, ShotEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun emulsions(): EmulsionDao
    abstract fun rolls(): RollDao
    abstract fun shots(): ShotDao

    companion object {
        fun build(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "rullino.db").build()
    }
}

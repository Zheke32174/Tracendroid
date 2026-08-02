package dev.pleiades.masamune.rom

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistence for the ROM image registry: Room + KSP only, no kotlinx-serialization — the same
 * discipline as the chat schema (Room through KSP; nothing reflective for R8 to break).
 *
 * The registry holds *metadata* only — a row per image the user added — pointing at the actual GB
 * file in app-scoped external storage (see [RomImage]). The image bytes never enter the database;
 * the database is a small index that survives restarts and a prefix rebuild alike.
 *
 * [arch] is stored as the enum *name* (a String column) rather than an ordinal, so reordering or
 * inserting an arch later cannot silently re-map existing rows.
 */
@Entity(tableName = "rom_images")
data class RomImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "path") val path: String,
    /** [RomArch.name]; parsed back defensively so an unknown stored value drops the row, never crashes. */
    @ColumnInfo(name = "arch") val arch: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Dao
interface RomImageDao {
    @Query("SELECT * FROM rom_images ORDER BY added_at DESC")
    fun observeAll(): Flow<List<RomImageEntity>>

    @Insert
    suspend fun insert(image: RomImageEntity): Long

    @Delete
    suspend fun delete(image: RomImageEntity)

    @Query("SELECT * FROM rom_images WHERE id = :id")
    suspend fun byId(id: Long): RomImageEntity?
}

@Database(entities = [RomImageEntity::class], version = 1, exportSchema = false)
abstract class RomImageDatabase : RoomDatabase() {
    abstract fun romImageDao(): RomImageDao

    companion object {
        @Volatile
        private var instance: RomImageDatabase? = null

        fun get(context: Context): RomImageDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RomImageDatabase::class.java,
                    "masamune-rom.db",
                ).build().also { instance = it }
            }
    }
}

/**
 * The registry, mapping the persisted [RomImageEntity] rows to the pure [RomImage] domain model
 * the UI works with. A row whose stored [RomImageEntity.arch] is not a known [RomArch] is dropped
 * from the observed list rather than crashing the surface — the honest failure mode for a
 * corrupted or forward-versioned row.
 */
class RomImageStore(private val dao: RomImageDao) {

    /** Live registry contents, newest first. Empty on a clean install — nothing is bundled. */
    val images: Flow<List<RomImage>> = dao.observeAll().map { rows ->
        rows.mapNotNull { it.toDomainOrNull() }
    }

    /** Register a newly added image. Returns its assigned row id. */
    suspend fun add(name: String, path: String, arch: RomArch, sizeBytes: Long): Long =
        dao.insert(
            RomImageEntity(
                name = name,
                path = path,
                arch = arch.name,
                sizeBytes = sizeBytes,
                addedAt = System.currentTimeMillis(),
            ),
        )

    /** Forget an image from the registry. The caller deletes the file; this drops the index row. */
    suspend fun remove(id: Long) {
        dao.byId(id)?.let { dao.delete(it) }
    }

    private fun RomImageEntity.toDomainOrNull(): RomImage? {
        val parsedArch = runCatching { RomArch.valueOf(arch) }.getOrNull() ?: return null
        return RomImage(id = id, name = name, path = path, arch = parsedArch, sizeBytes = sizeBytes)
    }

    companion object {
        fun get(context: Context): RomImageStore =
            RomImageStore(RomImageDatabase.get(context).romImageDao())
    }
}

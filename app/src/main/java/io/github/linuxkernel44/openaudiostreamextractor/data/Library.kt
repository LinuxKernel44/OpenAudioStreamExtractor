package io.github.linuxkernel44.openaudiostreamextractor.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pageUrl: String,
    val streamUrl: String,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val kind: String? = null,
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastCheckedAt: Long = System.currentTimeMillis()
)

@Dao
interface StationDao {
    @Query(
        "SELECT * FROM stations WHERE (:q = '' OR name LIKE '%' || :q || '%' OR streamUrl LIKE '%' || :q || '%' OR pageUrl LIKE '%' || :q || '%') " +
            "AND (:favoritesOnly = 0 OR favorite = 1) ORDER BY favorite DESC, name COLLATE NOCASE ASC"
    )
    fun observe(q: String, favoritesOnly: Boolean): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<StationEntity>

    @Query("SELECT * FROM stations WHERE streamUrl = :url LIMIT 1")
    suspend fun findByStream(url: String): StationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(station: StationEntity): Long

    @Update
    suspend fun update(station: StationEntity)

    @Query("DELETE FROM stations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [StationEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stations(): StationDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "oase.db").build()
    }
}

package app.scanova.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import app.scanova.core.database.entity.SyncMetaEntity

@Dao
interface SyncMetaDao {

    @Query("SELECT value FROM sync_meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: SyncMetaEntity)

    @Query("DELETE FROM sync_meta")
    suspend fun deleteAll()
}

package app.scanova.core.database.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** Small named values the sync engine needs between runs (for example the pull cursor). */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

package app.scanova.core.database

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase
import app.scanova.core.database.dao.DocumentDao
import app.scanova.core.database.dao.SyncMetaDao
import app.scanova.core.database.dao.TextIndexDao
import app.scanova.core.database.entity.DocumentEntity
import app.scanova.core.database.entity.DocumentFtsEntity
import app.scanova.core.database.entity.PageEntity
import app.scanova.core.database.entity.SyncMetaEntity

/**
 * The phone's local database. The file is encrypted as a whole by SQLCipher;
 * see [app.scanova.core.database.di.DatabaseModule] for how the key is supplied.
 */
@Database(
    entities = [
        DocumentEntity::class,
        PageEntity::class,
        DocumentFtsEntity::class,
        SyncMetaEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        // 2: pages keep their original and edit recipe; documents track page-list changes.
        AutoMigration(from = 1, to = 2),
        // 3: documents remember whether their pages still have to be restored from the server.
        AutoMigration(from = 2, to = 3),
        // 4: background text indexing (pages remember which image was read; documents which rules analysed them).
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class ScanovaDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    abstract fun syncMetaDao(): SyncMetaDao

    abstract fun textIndexDao(): TextIndexDao

    companion object {
        const val FILE_NAME = "scanova.db"
    }
}

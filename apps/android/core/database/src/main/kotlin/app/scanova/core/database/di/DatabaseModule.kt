package app.scanova.core.database.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import app.scanova.core.database.ScanovaDatabase
import app.scanova.core.database.dao.DocumentDao
import app.scanova.core.database.dao.SyncMetaDao
import app.scanova.core.database.dao.TextIndexDao
import app.scanova.core.security.keys.DatabasePassphraseStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

/**
 * SQLCipher driver that fetches its key on the first connection instead of at
 * injection time. Unwrapping the key is a Keystore operation and the first
 * query always runs on a background thread, so the main thread never waits
 * for secure hardware.
 */
private class LazySqlCipherDriver(private val keySpec: () -> ByteArray) : SQLiteDriver {
    private val delegate: SQLCipherDriver by lazy {
        System.loadLibrary("sqlcipher")
        SQLCipherDriver(keySpec(), null, null)
    }

    override fun open(fileName: String): SQLiteConnection = delegate.open(fileName)

    // SQLCipher manages its own pool of connections to the encrypted file.
    override val hasConnectionPool: Boolean get() = true
}

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphraseStore: DatabasePassphraseStore,
    ): ScanovaDatabase =
        Room.databaseBuilder<ScanovaDatabase>(context, ScanovaDatabase.FILE_NAME)
            .setDriver(LazySqlCipherDriver(passphraseStore::rawKeySpec))
            .build()

    @Provides
    fun provideDocumentDao(database: ScanovaDatabase): DocumentDao = database.documentDao()

    @Provides
    fun provideSyncMetaDao(database: ScanovaDatabase): SyncMetaDao = database.syncMetaDao()

    @Provides
    fun provideTextIndexDao(database: ScanovaDatabase): TextIndexDao = database.textIndexDao()
}

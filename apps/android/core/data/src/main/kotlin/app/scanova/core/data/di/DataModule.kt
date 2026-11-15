package app.scanova.core.data.di

import android.content.Context
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.coroutines.ApplicationScope
import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.auth.AuthRepository
import app.scanova.core.data.backup.BackupRepository
import app.scanova.core.data.auth.AuthRepositoryImpl
import app.scanova.core.data.auth.DeviceInfoProvider
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.documents.DocumentRepositoryImpl
import app.scanova.core.data.pairing.PairingRepository
import app.scanova.core.data.pairing.PairingRepositoryImpl
import app.scanova.core.data.scan.ScanRepository
import app.scanova.core.data.scan.ScanRepositoryImpl
import app.scanova.core.data.settings.SettingsRepository
import app.scanova.core.data.settings.SettingsRepositoryImpl
import app.scanova.core.data.storage.DocumentFiles
import app.scanova.core.data.storage.ExportFiles
import app.scanova.core.data.storage.LocalDataCleaner
import app.scanova.core.data.sync.RemoteSyncApi
import app.scanova.core.data.sync.RoomSyncStore
import app.scanova.core.data.sync.SyncEngine
import app.scanova.core.data.sync.SyncFiles
import app.scanova.core.data.sync.SyncRunner
import app.scanova.core.data.update.AppUpdater
import app.scanova.core.data.sync.SyncScheduler
import app.scanova.core.data.sync.WorkManagerSyncScheduler
import app.scanova.core.database.ScanovaDatabase
import app.scanova.core.database.dao.DocumentDao
import app.scanova.core.database.dao.SyncMetaDao
import app.scanova.core.network.ScanovaRemote
import app.scanova.core.network.ServerConfigStore
import app.scanova.core.network.auth.SessionEvents
import app.scanova.core.network.tls.CaCertificateFetcher
import app.scanova.core.security.LocalVault
import app.scanova.core.security.file.EncryptedFileStore
import app.scanova.core.security.store.EncryptedKeyValueStore
import app.scanova.core.security.store.TokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.InputStream
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {

    @Provides
    @Singleton
    fun provideSyncScheduler(@ApplicationContext context: Context): SyncScheduler =
        WorkManagerSyncScheduler(context)

    @Provides
    @Singleton
    fun provideExportFiles(@ApplicationContext context: Context): ExportFiles = ExportFiles(context)

    @Provides
    @Singleton
    fun provideDocumentFiles(
        dao: DocumentDao,
        files: EncryptedFileStore,
        dispatchers: AppDispatchers,
    ): DocumentFiles = DocumentFiles(dao, files, dispatchers)

    @Provides
    @Singleton
    fun provideLocalDataCleaner(
        @ApplicationContext context: Context,
        database: ScanovaDatabase,
        vault: LocalVault,
        syncScheduler: SyncScheduler,
        exports: ExportFiles,
        dispatchers: AppDispatchers,
        syncRunner: SyncRunner,
    ): LocalDataCleaner = LocalDataCleaner(context, database, vault, syncScheduler, exports, dispatchers, syncRunner)

    @Provides
    @Singleton
    fun provideAppUpdater(
        @ApplicationContext context: Context,
        remote: ScanovaRemote,
        values: EncryptedKeyValueStore,
        dispatchers: AppDispatchers,
    ): AppUpdater = AppUpdater(context, remote, values, dispatchers)

    @Provides
    @Singleton
    fun provideSyncRunner(
        dao: DocumentDao,
        metaDao: SyncMetaDao,
        documentFiles: DocumentFiles,
        files: EncryptedFileStore,
        remote: ScanovaRemote,
        clock: TimeSource,
        values: EncryptedKeyValueStore,
    ): SyncRunner = SyncRunner(
        SyncEngine(
            store = RoomSyncStore(dao, metaDao, documentFiles, clock),
            api = RemoteSyncApi(remote),
            files = object : SyncFiles {
                override fun exists(name: String): Boolean = files.exists(name)

                override fun open(name: String): InputStream = files.openRead(name)
            },
        ),
        // Record when a backup last finished completely (Settings and the library show it).
        onComplete = { values.put(BackupRepository.KEY_LAST_BACKUP, clock.now().toEpochMilli().toString()) },
    )

    @Provides
    @Singleton
    fun provideBackupRepository(
        tokenStore: TokenStore,
        values: EncryptedKeyValueStore,
        documents: DocumentRepository,
        syncScheduler: SyncScheduler,
    ): BackupRepository = BackupRepository(tokenStore, values, documents, syncScheduler)

    @Provides
    @Singleton
    fun providePairingRepository(
        configStore: ServerConfigStore,
        values: EncryptedKeyValueStore,
        tokenStore: TokenStore,
        remote: ScanovaRemote,
        caFetcher: CaCertificateFetcher,
        cleaner: LocalDataCleaner,
        dispatchers: AppDispatchers,
    ): PairingRepository =
        PairingRepositoryImpl(configStore, values, tokenStore, remote, caFetcher, cleaner, dispatchers)

    @Provides
    @Singleton
    fun provideAuthRepository(
        @ApplicationContext context: Context,
        remote: ScanovaRemote,
        tokenStore: TokenStore,
        values: EncryptedKeyValueStore,
        cleaner: LocalDataCleaner,
        syncScheduler: SyncScheduler,
        dispatchers: AppDispatchers,
        sessionEvents: SessionEvents,
        @ApplicationScope scope: CoroutineScope,
    ): AuthRepository = AuthRepositoryImpl(
        remote = remote,
        tokenStore = tokenStore,
        values = values,
        deviceInfo = DeviceInfoProvider(context),
        cleaner = cleaner,
        syncScheduler = syncScheduler,
        dispatchers = dispatchers,
        sessionEvents = sessionEvents,
        scope = scope,
    )

    @Provides
    @Singleton
    fun provideDocumentRepository(
        dao: DocumentDao,
        files: EncryptedFileStore,
        documentFiles: DocumentFiles,
        exports: ExportFiles,
        remote: ScanovaRemote,
        syncScheduler: SyncScheduler,
        syncRunner: SyncRunner,
        dispatchers: AppDispatchers,
        clock: TimeSource,
    ): DocumentRepository = DocumentRepositoryImpl(
        dao, files, documentFiles, exports, remote, syncScheduler, syncRunner, dispatchers, clock,
    )

    @Provides
    @Singleton
    fun provideScanRepository(
        dao: DocumentDao,
        files: EncryptedFileStore,
        syncScheduler: SyncScheduler,
        dispatchers: AppDispatchers,
        clock: TimeSource,
    ): ScanRepository = ScanRepositoryImpl(dao, files, syncScheduler, dispatchers, clock)

    @Provides
    @Singleton
    fun provideSettingsRepository(
        values: EncryptedKeyValueStore,
        dispatchers: AppDispatchers,
    ): SettingsRepository = SettingsRepositoryImpl(values, dispatchers)
}

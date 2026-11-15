package app.scanova.core.network.di

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.scanova.core.network.ScanovaClientFactory
import app.scanova.core.network.ScanovaRemote
import app.scanova.core.network.ServerConfigStore
import app.scanova.core.network.api.ApiCallExecutor
import app.scanova.core.network.api.LocalNetworkAccess
import app.scanova.core.network.auth.SessionEvents
import app.scanova.core.network.tls.CaCertificateFetcher
import app.scanova.core.security.store.EncryptedKeyValueStore
import app.scanova.core.security.store.TokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {

    /** First Android version that enforces the local-network permission (Android 17). */
    private const val LOCAL_NETWORK_PERMISSION_SDK = 37
    private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    @Provides
    @Singleton
    fun provideLocalNetworkAccess(@ApplicationContext context: Context): LocalNetworkAccess =
        LocalNetworkAccess {
            Build.VERSION.SDK_INT >= LOCAL_NETWORK_PERMISSION_SDK &&
                context.checkSelfPermission(ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        }

    @Provides
    @Singleton
    fun provideSessionEvents(): SessionEvents = SessionEvents()

    @Provides
    @Singleton
    fun provideServerConfigStore(store: EncryptedKeyValueStore): ServerConfigStore = ServerConfigStore(store)

    @Provides
    @Singleton
    fun provideCaCertificateFetcher(localNetworkAccess: LocalNetworkAccess): CaCertificateFetcher =
        CaCertificateFetcher(localNetworkAccess)

    @Provides
    @Singleton
    fun provideRemote(
        configStore: ServerConfigStore,
        tokenStore: TokenStore,
        sessionEvents: SessionEvents,
        localNetworkAccess: LocalNetworkAccess,
    ): ScanovaRemote = ScanovaRemote(
        currentConfig = configStore::current,
        clientFactory = ScanovaClientFactory(tokenStore, sessionEvents),
        executor = ApiCallExecutor(localNetworkAccess),
    )
}

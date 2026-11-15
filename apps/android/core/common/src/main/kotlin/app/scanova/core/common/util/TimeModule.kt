package app.scanova.core.common.util

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object TimeModule {

    @Provides
    @Singleton
    fun provideTimeSource(): TimeSource = TimeSource { Instant.now() }
}

package app.scanova.core.ml.di

import app.scanova.core.ml.text.MlKitTextEngine
import app.scanova.core.ml.text.TextEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MlModule {
    @Binds
    abstract fun bindTextEngine(engine: MlKitTextEngine): TextEngine
}

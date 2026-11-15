package app.scanova.core.security.di

import android.content.Context
import app.scanova.core.security.LocalVault
import app.scanova.core.security.file.EncryptedFileStore
import app.scanova.core.security.keys.DatabasePassphraseStore
import app.scanova.core.security.keys.TinkKeysets
import app.scanova.core.security.keystore.AndroidKeystoreAead
import app.scanova.core.security.store.EncryptedKeyValueStore
import app.scanova.core.security.store.EncryptedTokenStore
import app.scanova.core.security.store.TokenStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.StreamingAead
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel
import javax.inject.Qualifier
import javax.inject.Singleton

/** The hardware-backed key that wraps every other key. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MasterKey

/**
 * Injection must stay cheap: unwrapping a keyset is a Keystore call (slow on
 * StrongBox) and belongs on a background thread. These wrappers postpone it
 * until the first real encryption or decryption, which repositories always
 * perform off the main thread.
 */
private class LazyAead(provider: () -> Aead) : Aead {
    private val delegate by lazy(provider)

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray =
        delegate.encrypt(plaintext, associatedData)

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
        delegate.decrypt(ciphertext, associatedData)
}

private class LazyStreamingAead(provider: () -> StreamingAead) : StreamingAead {
    private val delegate by lazy(provider)

    override fun newEncryptingChannel(target: WritableByteChannel, associatedData: ByteArray): WritableByteChannel =
        delegate.newEncryptingChannel(target, associatedData)

    override fun newSeekableDecryptingChannel(
        source: SeekableByteChannel,
        associatedData: ByteArray,
    ): SeekableByteChannel = delegate.newSeekableDecryptingChannel(source, associatedData)

    override fun newDecryptingChannel(source: ReadableByteChannel, associatedData: ByteArray): ReadableByteChannel =
        delegate.newDecryptingChannel(source, associatedData)

    override fun newEncryptingStream(target: OutputStream, associatedData: ByteArray): OutputStream =
        delegate.newEncryptingStream(target, associatedData)

    override fun newDecryptingStream(source: InputStream, associatedData: ByteArray): InputStream =
        delegate.newDecryptingStream(source, associatedData)
}

@Module
@InstallIn(SingletonComponent::class)
internal object SecurityModule {

    // Everything lives under noBackupFilesDir: even if a backup were ever
    // enabled by mistake, these directories are excluded by the platform.
    private fun keyDirectory(context: Context) = File(context.noBackupFilesDir, "keys")

    @Provides
    @Singleton
    @MasterKey
    fun provideMasterKey(@ApplicationContext context: Context): Aead = AndroidKeystoreAead(context)

    @Provides
    @Singleton
    fun provideKeysets(
        @ApplicationContext context: Context,
        @MasterKey masterKey: Aead,
    ): TinkKeysets = TinkKeysets(keyDirectory(context), masterKey)

    @Provides
    @Singleton
    fun provideDatabasePassphraseStore(
        @ApplicationContext context: Context,
        @MasterKey masterKey: Aead,
    ): DatabasePassphraseStore = DatabasePassphraseStore(File(keyDirectory(context), "db.key"), masterKey)

    @Provides
    @Singleton
    fun provideFileStore(
        @ApplicationContext context: Context,
        keysets: TinkKeysets,
    ): EncryptedFileStore =
        EncryptedFileStore(File(context.noBackupFilesDir, "vault"), LazyStreamingAead { keysets.streamingAead })

    @Provides
    @Singleton
    fun provideKeyValueStore(
        @ApplicationContext context: Context,
        keysets: TinkKeysets,
    ): EncryptedKeyValueStore =
        EncryptedKeyValueStore(File(context.noBackupFilesDir, "state/values.enc"), LazyAead { keysets.aead })

    @Provides
    @Singleton
    fun provideTokenStore(store: EncryptedKeyValueStore): TokenStore = EncryptedTokenStore(store)

    @Provides
    @Singleton
    fun provideLocalVault(
        @ApplicationContext context: Context,
        files: EncryptedFileStore,
        values: EncryptedKeyValueStore,
    ): LocalVault = LocalVault(
        files = files,
        values = values,
        keyDirectory = keyDirectory(context),
        deleteMasterKey = { AndroidKeystoreAead(context).deleteKey() },
    )
}

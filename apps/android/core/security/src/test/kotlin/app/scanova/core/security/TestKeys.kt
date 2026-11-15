package app.scanova.core.security

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters

/**
 * Stands in for the Android Keystore key, which does not exist on the JVM.
 * Everything above the master key (keysets, stores, formats) is the real code.
 */
internal fun newInMemoryMasterKey(): Aead {
    AeadConfig.register()
    return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
}

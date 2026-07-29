package dev.qelg.harnessandroid

import org.junit.Assert.assertNotNull
import org.junit.Test

class SecureCredentialsDependencyTest {
    @Test
    fun androidKeysetManagerRequiredByEncryptedPreferencesIsPackaged() {
        assertNotNull(
            Class.forName(
                "com.google.crypto.tink.integration.android.AndroidKeysetManager\$Builder"
            )
        )
    }
}

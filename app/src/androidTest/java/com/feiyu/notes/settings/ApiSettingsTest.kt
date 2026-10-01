package com.feiyu.notes.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.security.keystore.KeyInfo
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.feiyu.notes.ai.AiDefaults
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory

/** Synthetic keys and unique aliases only; never opens production credential preferences. */
class ApiSettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "credential_test_${UUID.randomUUID()}"
    private val alias = "feiyu_$name"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val settings = ApiSettings(context, name, alias)
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val fake = "synthetic-key-for-instrumentation-only"

    @After fun cleanUp() {
        context.deleteSharedPreferences(name)
        keyStore.deleteEntry(alias)
    }

    @Test fun roundTripUsesPrivateCiphertextAndNonExportableAesKey() {
        settings.save(fake, AiDefaults.MODEL)
        val first = prefs.getString("api_key_cipher", null)
        val file = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
        assertFalse(file.readText().contains(fake))
        val stat = Os.stat(file.path)
        assertEquals(0, stat.st_mode and (OsConstants.S_IROTH or OsConstants.S_IWOTH))
        assertEquals(android.os.Process.myUid(), stat.st_uid)
        // Android may grant the owning app's group access; it is not another app's group.
        assertEquals(android.os.Process.myUid(), stat.st_gid)
        assertEquals(fake, ApiSettings(context, name, alias).load()!!.apiKey)
        assertTrue(settings.hasKey())
        settings.save(fake, AiDefaults.MODEL)
        assertNotEquals(first, prefs.getString("api_key_cipher", null))
        val key = keyStore.getKey(alias, null) as javax.crypto.SecretKey
        assertNull(key.encoded)
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertTrue(info.blockModes.contains("GCM"))
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.photos", 0)!!
        assertFalse(provider.exported)
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, provider.authority, file) }
    }

    @Test fun tamperingAndKeyLossRequireReentryRatherThanUsingBadCredentials() {
        settings.save(fake, AiDefaults.MODEL)
        val sealed = Base64.getDecoder().decode(prefs.getString("api_key_cipher", null))
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        prefs.edit().putString("api_key_cipher", Base64.getEncoder().encodeToString(sealed)).commit()
        assertNull(settings.load())
        assertFalse(settings.hasKey())
        settings.save(fake, AiDefaults.MODEL)
        keyStore.deleteEntry(alias)
        assertNull(settings.load())
        assertFalse(settings.hasKey())
        assertFalse(keyStore.containsAlias(alias))
        settings.save(fake, AiDefaults.MODEL)
        assertEquals(fake, settings.load()!!.apiKey)
        settings.save("", AiDefaults.MODEL)
        assertFalse(prefs.contains("api_key_cipher"))
    }
}

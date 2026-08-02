package dev.pleiades.masamune.apps

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings

/**
 * The real, device-backed [SystemSettings] — the Android glue that turns the plain-data contract
 * into `Settings.System/Secure/Global` reads and writes, `AudioManager` ringer control, a
 * `SystemProperties` reflective read, and a `Locale` lookup.
 *
 * This is the only file in the slice that touches `android.*`, and it is compile-only from the unit
 * tests' point of view: the blocks never see it, they see [SystemSettings]. Keeping every framework
 * call on this side of the seam is what lets
 * [dev.pleiades.masamune.flow.runtime.impl.SettingsBlocks] stay JVM-testable against a fake.
 *
 * ### Honest boundaries — a denied write is data, never a thrown surprise
 *  - **WRITE_SETTINGS is checked, not assumed.** `Settings.System.canWrite(context)` is consulted
 *    before a `Settings.System` write and, if it is `false`, the method returns
 *    `SettingWrite(ok = false, reason = "WRITE_SETTINGS not granted")` instead of attempting a write
 *    that would throw. The `SECURE`/`GLOBAL` tables need `WRITE_SECURE_SETTINGS`, which an ordinary
 *    app cannot hold; those writes are attempted and any `SecurityException` is caught and reported
 *    as a `reason`, so the block Fails by name rather than crashing.
 *  - **Ringer is `AudioManager`, not the settings store**, so it is not WRITE_SETTINGS-gated — but
 *    setting SILENT/VIBRATE can still be refused when a Do-Not-Disturb policy owns the ringer, which
 *    surfaces as a caught `SecurityException` → `SettingWrite(ok = false, ...)`.
 *  - **Reads that find nothing return `null`**, never a fabricated empty value: a missing settings
 *    key, an unreadable brightness, an unset system property all propagate as `null` to a named Fail.
 */
class AndroidSystemSettings(private val context: Context) : SystemSettings {

    private val resolver get() = context.contentResolver

    override suspend fun getSetting(namespace: SettingNamespace, name: String): String? = when (namespace) {
        SettingNamespace.SYSTEM -> Settings.System.getString(resolver, name)
        SettingNamespace.SECURE -> Settings.Secure.getString(resolver, name)
        SettingNamespace.GLOBAL -> Settings.Global.getString(resolver, name)
    }

    override suspend fun putSetting(
        namespace: SettingNamespace,
        name: String,
        value: String,
    ): SettingWrite {
        // Settings.System is the WRITE_SETTINGS-gated table; check up front so the honest reason is
        // "not granted" rather than a raw SecurityException. Secure/Global need WRITE_SECURE_SETTINGS
        // an app cannot hold; they are attempted and any refusal is caught below.
        if (namespace == SettingNamespace.SYSTEM && !Settings.System.canWrite(context)) {
            return SettingWrite(ok = false, reason = "WRITE_SETTINGS not granted")
        }
        return try {
            val ok = when (namespace) {
                SettingNamespace.SYSTEM -> Settings.System.putString(resolver, name, value)
                SettingNamespace.SECURE -> Settings.Secure.putString(resolver, name, value)
                SettingNamespace.GLOBAL -> Settings.Global.putString(resolver, name, value)
            }
            if (ok) SettingWrite(ok = true) else SettingWrite(ok = false, reason = "settings store rejected the write")
        } catch (e: SecurityException) {
            SettingWrite(ok = false, reason = e.message ?: "not permitted to write $namespace/$name")
        }
    }

    override suspend fun screenBrightness(): Int? = try {
        Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (_: Settings.SettingNotFoundException) {
        null
    }

    override suspend fun setScreenBrightness(value: Int): SettingWrite {
        if (!Settings.System.canWrite(context)) {
            return SettingWrite(ok = false, reason = "WRITE_SETTINGS not granted")
        }
        return try {
            val ok = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
            if (ok) SettingWrite(ok = true) else SettingWrite(ok = false, reason = "settings store rejected brightness")
        } catch (e: SecurityException) {
            SettingWrite(ok = false, reason = e.message ?: "not permitted to set brightness")
        }
    }

    override suspend fun screenOffTimeoutMs(): Long? = try {
        Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT).toLong()
    } catch (_: Settings.SettingNotFoundException) {
        null
    }

    override suspend fun setScreenOffTimeoutMs(ms: Long): SettingWrite {
        if (!Settings.System.canWrite(context)) {
            return SettingWrite(ok = false, reason = "WRITE_SETTINGS not granted")
        }
        return try {
            val ok = Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, ms.toInt())
            if (ok) SettingWrite(ok = true) else SettingWrite(ok = false, reason = "settings store rejected timeout")
        } catch (e: SecurityException) {
            SettingWrite(ok = false, reason = e.message ?: "not permitted to set screen-off timeout")
        }
    }

    override suspend fun ringerMode(): RingerMode? {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> RingerMode.NORMAL
            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
            AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
            else -> null
        }
    }

    override suspend fun setRingerMode(mode: RingerMode): SettingWrite {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return SettingWrite(ok = false, reason = "AudioManager unavailable")
        val value = when (mode) {
            RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
            RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
        }
        return try {
            audio.ringerMode = value
            SettingWrite(ok = true)
        } catch (e: SecurityException) {
            // Setting SILENT/VIBRATE can be refused when a Do-Not-Disturb policy owns the ringer.
            SettingWrite(ok = false, reason = e.message ?: "not permitted to set ringer mode (DND policy?)")
        }
    }

    override suspend fun systemProperty(key: String): String? {
        // Read-only reflective access to android.os.SystemProperties.get(String): reading a property
        // needs no shell (only writing does), and an unset property returns "" which we surface as null.
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java)
            (get.invoke(null, key) as? String)?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun systemLanguage(): String? {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        return locale?.toLanguageTag()?.takeIf { it.isNotBlank() && it != "und" }
    }
}

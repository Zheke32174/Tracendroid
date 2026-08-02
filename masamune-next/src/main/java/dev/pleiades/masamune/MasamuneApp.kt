package dev.pleiades.masamune

import android.app.Application
import dev.pleiades.masamune.ai.ProviderStore
import dev.pleiades.masamune.core.capability.CapabilityGate
import dev.pleiades.masamune.fs.FileSystemRegistry

/**
 * Application entry point. Deliberately thin: it warms the three process-wide singletons and
 * does nothing else. No self-updater, no market client, no bundled-APK installer, no
 * accessibility provider binding — none of those ship in this module.
 */
class MasamuneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CapabilityGate.get(this)
        FileSystemRegistry.get(this)
        ProviderStore.get(this)
    }
}

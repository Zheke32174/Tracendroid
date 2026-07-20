pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://api.xposed.info/") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
    }
}

rootProject.name = "Operit"
include(":app")
include(":dragonbones")
include(":terminal")
include(":mnn")
include(":llama")
include(":mmd")
include(":fbx")
include(":quickjs")
// Self-contained, in-process PTY terminal vendored from Xed-Editor (Termux-derived, GPLv3).
// These give Tracendroid its own embedded terminal so it no longer *requires* the external
// OperitTerminal companion app. See THIRD_PARTY_LICENSES.md / NOTICE for attribution.
include(":terminal-emulator")
include(":terminal-view")

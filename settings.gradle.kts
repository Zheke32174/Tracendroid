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
// masamune-next: from-scratch AI-harness module, built beside :app. :app is untouched.
include(":masamune-next")
include(":dragonbones")
include(":terminal")
include(":mnn")
include(":llama")
include(":mmd")
include(":fbx")
include(":quickjs")

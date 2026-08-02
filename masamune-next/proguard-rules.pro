# masamune-next R8 rules.
#
# The module deliberately has no reflective-serialization library (payloads are built
# and parsed with org.json), so the rule surface is small.

# Room generates implementations that are looked up by name at runtime.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# OkHttp / Okio ship their own consumer rules; silence the optional-platform warnings.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin coroutines debug agent is not shipped.
-dontwarn kotlinx.coroutines.debug.**

# Compose tooling previews are debug-only.
-dontwarn androidx.compose.ui.tooling.**

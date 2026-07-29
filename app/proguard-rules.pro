# LoopGuard keeps R8 disabled for release builds (see app/build.gradle.kts).
# These rules exist so that enabling shrinking later is a one-line change.
-keep class com.loopguard.app.data.** { *; }
-dontwarn org.jetbrains.annotations.**

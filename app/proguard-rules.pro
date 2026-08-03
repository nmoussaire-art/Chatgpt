# BatteryCast Quant — R8 configuration for the release build.
#
# The app is small and has no reflection-heavy dependencies, so most of this is defensive rather
# than required. Room and Hilt ship their own consumer rules and need nothing here.

# Enum constants are persisted to Room by *name*, and decoded by matching that name back. R8 must
# not rename them or the database would become unreadable after an update.
-keepclassmembers enum com.batterycast.quant.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public java.lang.String name();
}

# Room entities are constructed reflectively by generated code.
-keep class com.batterycast.quant.core.database.entity.** { *; }

# Kotlin metadata, needed for data-class copy/componentN used across module boundaries.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations

# WorkManager instantiates workers by class name.
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }

# Keep line numbers so a stack trace from a user is readable. Nothing is uploaded anywhere; this
# only helps if someone reports a crash by hand.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

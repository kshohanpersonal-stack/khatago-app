# KhataGo R8 rules.
#
# KhataGo is deliberately reflection-free except for two frameworks that ship their own
# consumer rules (Room, kotlinx.serialization), so the app adds almost nothing here.
# Anything below exists to keep crash reports readable and to stop R8 inlining away
# code that is only reachable from the manifest (workers, receivers).

# Keep the WorkManager entry points referenced from the manifest / Configuration.
-keep class com.khatago.finance.notify.** extends androidx.work.ListenableWorker { *; }
-keep class com.khatago.finance.notify.KhataGoWorkerFactory { *; }
-keep class com.khatago.finance.notify.DueSummaryReceiver { *; }

# Kotlin serialization keeps generated serializers, but keep the backup DTOs by name so a
# future serializer lookup cannot silently drop fields.
-keepclassmembers class com.khatago.finance.data.backup.** {
    @kotlinx.serialization.SerialName <fields>;
}

# Room generated implementations are instantiated reflectively by RoomDatabase.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Keep line numbers for readable, deobfuscated-stacktrace capable crash logs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontoptimize  # R8 still shrinks; full re-optimisation of Room code is not needed for an offline app.

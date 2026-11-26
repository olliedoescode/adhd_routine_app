# ---------------------------------------------------------------------------
# Room
# ---------------------------------------------------------------------------
# Room's generated `_Impl` classes are looked up by name at runtime, and the
# database class is instantiated reflectively by the builder.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.room.RoomDatabase_Impl { *; }
-keep class androidx.room.RoomDatabase { *; }

# Entity classes are read by generated code; keep their fields and the
# no-arg constructor Room synthesises for them.
-keep class com.adhd.routineplanner.data.model.** { *; }
-keepclassmembers class com.adhd.routineplanner.data.model.** { <init>(...); }

# Room's runtime annotations and the SQLite driver it reflects over.
-keep class androidx.room.** { *; }
-keep class androidx.sqlite.** { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Hilt / Dagger
# ---------------------------------------------------------------------------
# Generated components, modules and factories are all resolved by name.
-keep class dagger.hilt.** { *; }
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponentManager { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponentManagerHolder { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories$HiltViewModelFactory { *; }

# Hilt's generated `_HiltModules` and `_GeneratedInjector` types.
-keep class **_HiltModules { *; }
-keep class **_HiltModules$* { *; }
-keep class **_GeneratedInjector { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }
-keep class **_Impl { *; }

# `@AndroidEntryPoint` classes are instantiated by Hilt's generated managers.
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# Hilt's aggregating processor emits metadata that must survive shrinking.
-keep class hilt_aggregated_deps.** { *; }
-keep class dagger.hilt.android.internal.builders.** { *; }
-keep class dagger.hilt.android.internal.managers.** { *; }
-keep class dagger.hilt.android.internal.modules.** { *; }
-keep class dagger.hilt.android.internal.setup.** { *; }
-keep class dagger.hilt.android.internal.workers.** { *; }
-keep class dagger.hilt.android.internal.testing.** { *; }

# ---------------------------------------------------------------------------
# App entry points referenced only from the manifest / reflection
# ---------------------------------------------------------------------------
# BroadcastReceivers, Activities and the Application class are constructed by
# the framework from their manifest names, so R8 must not rename them.
-keep class com.adhd.routineplanner.App { *; }
-keep class com.adhd.routineplanner.MainActivity { *; }
-keep class com.adhd.routineplanner.alarm.** { *; }

# ---------------------------------------------------------------------------
# Kotlin / coroutines
# ---------------------------------------------------------------------------
# Coroutine state machines and Kotlin metadata are read reflectively.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# ---------------------------------------------------------------------------
# Compose
# ---------------------------------------------------------------------------
# Compose's runtime discovers composables and their default arguments by name.
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

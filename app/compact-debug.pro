# Retain JVM symbols for debugging and instrumentation's shared runtime.
-dontobfuscate
# Instrumentation reads generated flags even after the app folds its own reads.
-keep class io.toolbox.host.BuildConfig { *; }
# The focused runtime test constructs this shared model with Kotlin defaults.
-keep class io.toolbox.tool.packagekit.InstalledManifest { *; }
# Instrumentation shares the app's Kotlin library, including facades that the
# app's own call graph does not reference. Preserve that library's test ABI.
-keep class kotlin.** { *; }
# Coroutine test dispatchers implement interfaces absent from the app call graph.
# Both APKs share coroutines-core, so preserve its ABI for instrumentation.
-keep class kotlinx.coroutines.** { *; }
# Tests implement shared Kotlin interfaces and call their compatibility bridges.
-keep class **$DefaultImpls { *; }
# Compose's test SDK queries semantics/layout and controls platform roots through
# shared UI API absent from the app-only call graph. Private implementation and
# unrelated packages continue to shrink.
-keep class androidx.compose.ui.** {
    public *;
    protected *;
}
# Test idling observes snapshots and controls recomposition through this shared
# runtime API, including Snapshot.hasPendingChanges unused by the app itself.
-keep class androidx.compose.runtime.** {
    public *;
    protected *;
}

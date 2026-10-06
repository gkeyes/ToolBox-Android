# Retain JVM symbols for debugging and instrumentation's shared runtime.
-dontobfuscate
# Instrumentation shares the app's Kotlin library, including facades that the
# app's own call graph does not reference. Preserve that library's test ABI.
-keep class kotlin.** { *; }
# Coroutine test dispatchers implement interfaces absent from the app call graph.
# Both APKs share coroutines-core, so preserve its ABI for instrumentation.
-keep class kotlinx.coroutines.** { *; }
# Tests implement shared Kotlin interfaces and call their compatibility bridges.
-keep class **$DefaultImpls { *; }
# Compose's test SDK registers roots and replaces window recomposition factories
# through shared platform API absent from the app-only call graph. Preserve that
# API while private platform implementation and the rest of Compose still shrink.
-keep class androidx.compose.ui.platform.** {
    public *;
    protected *;
}
-keep class androidx.compose.ui.node.RootForTest** { *; }

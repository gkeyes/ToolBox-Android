# Retain JVM symbols for debugging and instrumentation's shared runtime.
-dontobfuscate
# AndroidJUnitRunner's TestDirCalculator accesses this Kotlin facade directly.
-keep class kotlin.LazyKt** { *; }

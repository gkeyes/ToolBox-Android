# Retain JVM symbols for debugging and instrumentation's shared runtime.
-dontobfuscate
# Instrumentation shares the app's Kotlin library, including facades that the
# app's own call graph does not reference. Preserve that library's test ABI.
-keep class kotlin.** { *; }

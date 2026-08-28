plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.screenshot) apply false
}

val verifySecurityInvariants by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails when phase source code requests forbidden capabilities or weakens WebView defaults."

    val guardedSources = fileTree(layout.projectDirectory) {
        include("*/src/main/**/*.kt", "*/src/main/**/*.java", "*/src/main/**/*.xml")
    }
    val verificationScript = layout.projectDirectory.file("scripts/verify-security-invariants.sh")
    val guardedWorkflow = layout.projectDirectory.file(".github/workflows/android.yml")

    inputs.files(guardedSources)
    inputs.file(verificationScript)
    inputs.file(guardedWorkflow)
    commandLine(
        "bash",
        verificationScript.asFile.absolutePath,
        layout.projectDirectory.asFile.absolutePath,
    )
}

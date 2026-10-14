import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val bundledExamplesDir = rootProject.layout.buildDirectory.dir("bundled-examples")
val verifyWebViewDebugging = providers.gradleProperty("toolbox.verifyWebViewDebugging").orNull == "true"

val ciRunId = providers.environmentVariable("GITHUB_RUN_ID").orNull?.toLongOrNull() ?: 0L

val stableSigningStoreFile = providers.environmentVariable("TOOLBOX_SIGNING_STORE_FILE").orNull
val stableSigningStorePassword = providers.environmentVariable("TOOLBOX_SIGNING_STORE_PASSWORD").orNull
val stableSigningKeyAlias = providers.environmentVariable("TOOLBOX_SIGNING_KEY_ALIAS").orNull
val stableSigningKeyPassword = providers.environmentVariable("TOOLBOX_SIGNING_KEY_PASSWORD").orNull
val stableSigningValueCount =
    listOf(
        stableSigningStoreFile,
        stableSigningStorePassword,
        stableSigningKeyAlias,
        stableSigningKeyPassword,
    ).count { !it.isNullOrBlank() }

check(stableSigningValueCount == 0 || stableSigningValueCount == 4) {
    "Stable APK signing requires store file, store password, key alias and key password together."
}
check(!verifyWebViewDebugging || stableSigningValueCount == 0) {
    "WebView verification must use the temporary test signing key, without stable signing credentials."
}

// The shipped examples are the targets marked "bundled" in scripts/ci/tbx-targets.json.
val tbxTargetsFile = rootProject.layout.projectDirectory.file("scripts/ci/tbx-targets.json")
val bundledExampleNames: List<String> =
    (JsonSlurper().parseText(providers.fileContents(tbxTargetsFile).asText.get()) as Map<*, *>)
        .filterValues { (it as Map<*, *>)["bundled"] == true }
        .keys
        .map { it as String }

val packageBundledExamples = tasks.register<Exec>("packageBundledExamples") {
    group = "build"
    description = "Packages the shipped ToolBox examples for Android assets."
    bundledExampleNames.forEach { name -> inputs.dir(rootProject.layout.projectDirectory.dir("examples/$name")) }
    inputs.file(tbxTargetsFile)
    inputs.file(rootProject.layout.projectDirectory.file("scripts/package-examples.sh"))
    outputs.dir(bundledExamplesDir)
    // Start from an empty directory so a renamed or un-bundled example's old .tbx is never shipped.
    val outputDirectory = bundledExamplesDir.get().asFile
    doFirst {
        outputDirectory.deleteRecursively()
        outputDirectory.mkdirs()
    }
    workingDir = rootProject.projectDir
    environment("TOOLBOX_EXAMPLE_OUTPUT_DIR", bundledExamplesDir.get().asFile.absolutePath)
    commandLine("bash", rootProject.layout.projectDirectory.file("scripts/package-examples.sh").asFile.absolutePath)
}

// BundledExamplesRegistryTest reads the registry at run time, so a registry change must re-run unit tests.
// Layout screenshots render on the JVM (Robolectric) and run in their own CI job, so the main unit tests skip them.
val layoutScreenshots = providers.gradleProperty("toolbox.screenshots").orNull == "true"

tasks.withType<Test>().configureEach {
    inputs.file(tbxTargetsFile).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("tbxTargets")
    // Robolectric's interceptors reflect into JDK internals that Java 21 closes by default; the
    // screenshots and the LocationManager-driven tests both need them.
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    )
    if (layoutScreenshots) {
        filter.includeTestsMatching("io.toolbox.host.screenshots.*")
        systemProperty("roborazzi.test.record", "true")
        // Hardware rendering makes Robolectric draw RenderNode layers (item animations, blur) in PixelCopy.
        systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        val screenshotDir = rootProject.layout.buildDirectory.dir("screenshots")
        systemProperty("toolbox.screenshots.dir", screenshotDir.get().asFile.absolutePath)
        // Declared so a cached test result restores the PNGs too.
        outputs.dir(screenshotDir).withPropertyName("layoutScreenshots")
        maxHeapSize = "4g"
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
            showCauses = true
        }
    } else {
        exclude("io/toolbox/host/screenshots/**")
    }
}

android {
    namespace = "io.toolbox.host"
    compileSdk = 37
    if (verifyWebViewDebugging) testBuildType = "verification"

    defaultConfig {
        applicationId = "io.toolbox.host"
        minSdk = 33
        targetSdk = 37
        versionCode = 86
        versionName = "0.13.10"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // In-app updates read this repository's releases and compare against the CI run that built the APK.
        buildConfigField("String", "UPDATE_REPOSITORY", "\"gkeyes/toolbox-android\"")
        buildConfigField("long", "BUILD_RUN_ID", "${ciRunId}L")
    }

    val stableSigningConfig =
        if (stableSigningValueCount == 4) {
            signingConfigs.create("stable") {
                storeFile = file(requireNotNull(stableSigningStoreFile))
                storePassword = stableSigningStorePassword
                keyAlias = stableSigningKeyAlias
                keyPassword = stableSigningKeyPassword
            }
        } else {
            null
        }

    buildTypes {
        // Debug keeps the default debug key: a debuggable build signed with the stable key could
        // replace a user's release install and expose its tool data over adb.
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            stableSigningConfig?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
            )
        }

        if (verifyWebViewDebugging) {
            create("verification") {
                initWith(getByName("debug"))
                isDebuggable = false
                signingConfig = signingConfigs.getByName("debug")
                matchingFallbacks.add("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Same cast-free path as core-data: AGP 9.4's typed source-set accessor can fail its cast.
    val mainSources = (sourceSets as NamedDomainObjectContainer<*>).getByName("main") as com.android.build.api.dsl.AndroidSourceSet
    mainSources.assets.directories.add(
        bundledExamplesDir.get().asFile.absolutePath,
    )
    mainSources.assets.directories.add(
        rootProject.layout.projectDirectory.dir("sdk/help").asFile.absolutePath,
    )
}

tasks.configureEach {
    val consumesBundledExamples =
        (name.startsWith("merge") && name.endsWith("Assets")) ||
            name.startsWith("lintAnalyze") ||
            name.startsWith("lintVital") ||
            (name.startsWith("generate") && (name.endsWith("LintReportModel") || name.endsWith("LintVitalReportModel")))
    if (consumesBundledExamples) {
        dependsOn(packageBundledExamples)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(project(":core-ui"))
    implementation(project(":tool-package"))
    implementation(project(":tool-runtime"))
    implementation(project(":tool-api"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.miuix.nav)
    implementation(libs.okhttp)
    implementation(libs.focus.api)
    implementation(libs.androidsvg)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

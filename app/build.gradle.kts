import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.screenshot)
}

val bundledExamplesDir = rootProject.layout.buildDirectory.dir("bundled-examples")

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

val packageBundledExamples by tasks.registering(Exec::class) {
    group = "build"
    description = "Packages the four shipped ToolBox examples for Android assets."
    inputs.dir(rootProject.layout.projectDirectory.dir("examples/position-calculator"))
    inputs.dir(rootProject.layout.projectDirectory.dir("examples/quick-notes"))
    inputs.dir(rootProject.layout.projectDirectory.dir("examples/background-task-demo"))
    inputs.dir(rootProject.layout.projectDirectory.dir("examples/notification-lab"))
    inputs.file(rootProject.layout.projectDirectory.file("scripts/package-examples.sh"))
    outputs.dir(bundledExamplesDir)
    workingDir = rootProject.projectDir
    environment("TOOLBOX_EXAMPLE_OUTPUT_DIR", bundledExamplesDir.get().asFile.absolutePath)
    commandLine("bash", rootProject.layout.projectDirectory.file("scripts/package-examples.sh").asFile.absolutePath)
}

android {
    namespace = "io.toolbox.host"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.toolbox.host"
        minSdk = 33
        targetSdk = 37
        versionCode = 8
        versionName = "0.3.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        debug {
            stableSigningConfig?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            stableSigningConfig?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("candidate") {
            initWith(getByName("release"))
            signingConfig = stableSigningConfig ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    testOptions {
        animationsDisabled = true
    }

    sourceSets.getByName("main").assets.directories.add(
        bundledExamplesDir.get().asFile.absolutePath,
    )
    sourceSets.getByName("main").assets.directories.add(
        rootProject.layout.projectDirectory.dir("sdk/help").asFile.absolutePath,
    )
    sourceSets.getByName("test").resources.srcDir(
        rootProject.layout.projectDirectory.dir("sdk/help"),
    )
}

tasks.configureEach {
    val consumesBundledExamples =
        (name.startsWith("merge") && name.endsWith("Assets")) ||
            name.startsWith("lintVital") ||
            (name.startsWith("generate") && name.endsWith("LintVitalReportModel"))
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
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.miuix.nav)
    implementation(libs.okhttp)
    implementation(libs.focus.api)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.ext.junit)

    screenshotTestImplementation(platform(libs.androidx.compose.bom))
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
    screenshotTestImplementation(libs.screenshot.validation.api)
}

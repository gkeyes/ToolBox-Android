import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.screenshot)
}

val packageBundledExamples by tasks.registering(Exec::class) {
    group = "build"
    description = "Packages the three shipped ToolBox examples for Android assets."
    inputs.dir(rootProject.layout.projectDirectory.dir("examples/position-calculator"))
    inputs.dir(rootProject.layout.projectDirectory.dir("examples/quick-notes"))
    inputs.dir(rootProject.layout.projectDirectory.dir("examples/background-task-demo"))
    inputs.file(rootProject.layout.projectDirectory.file("scripts/package-examples.sh"))
    outputs.dir(rootProject.layout.buildDirectory.dir("examples"))
    workingDir = rootProject.projectDir
    commandLine("bash", rootProject.layout.projectDirectory.file("scripts/package-examples.sh").asFile.absolutePath)
}

android {
    namespace = "io.toolbox.host"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.toolbox.host"
        minSdk = 33
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("candidate") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
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
        rootProject.layout.buildDirectory.dir("examples").get().asFile.absolutePath,
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

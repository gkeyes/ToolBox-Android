import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.toolbox.core.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.ui)
    api(libs.miuix.ui)
    api(libs.miuix.preference)
    // Real-time Liquid Glass: AGSL refraction (lens), blur and vibrancy over a recorded layer.
    api(libs.backdrop)
    api(libs.kyant.shapes)
    // Predictive back for sheets; the app module already ships the same artifact.
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.material.icons.extended)
    // Choreographer-aligned awaitFrame for the vendored liquid glass components.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

}

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.toolbox.tool.runtime"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(project(":tool-api"))
    implementation(project(":tool-package"))
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.core)
}

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.toolbox.tool.runtime"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(project(":tool-package"))
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

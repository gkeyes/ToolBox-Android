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
    implementation(project(":tool-api"))
    implementation(project(":tool-package"))
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

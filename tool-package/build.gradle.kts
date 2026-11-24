plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.toolbox.tool.packagekit"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

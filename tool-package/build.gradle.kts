plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.toolbox.tool.packagekit"
    compileSdk = 37
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

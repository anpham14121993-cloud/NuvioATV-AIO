plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.nuvio.app.vieneu"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += "arm64-v8a"
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.0")
    compileOnly("androidx.annotation:annotation:1.9.1")
}

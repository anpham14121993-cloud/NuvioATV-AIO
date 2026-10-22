plugins {
    id("com.android.library")
}

android {
    namespace = "com.nuvio.app.vieneu"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.0")
    compileOnly("androidx.annotation:annotation:1.9.1")
}

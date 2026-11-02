// A stand-in Android phone for exercising the head unit: the phone half of Android Auto, plus a
// generated test signal in place of a real screen capture. Development tool, not a product.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.diplay.aapphone"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.diplay.aapphone"
        // MediaCodec's Image-based input and the AAC/H.264 software encoders are solid from 21.
        minSdk = 21
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // :shared asks for it, and its java.time use would fail at runtime on 21 without it.
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
}

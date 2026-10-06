plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.trex_kotlin"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.trex_kotlin"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.3.0-preview.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 평가 설치는 사용자의 기존 앱·운동 기록과 분리한다.
            if (project.hasProperty("postureReplay")) applicationIdSuffix = ".replay"
        }
        release {
            // 체험판은 기존 설치본과 같은 개발 인증서(debug 키스토어)로 서명한다 — 삭제 없이 업데이트되고 앱 내 업데이트(AppUpdate)가 서명을 대조한다.
            // 운영 배포용 별도 서명키는 아직 없다(docs/ANDROID_PREVIEW_RELEASE_1_3_0_6.md).
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // MediaPipe 모델(.task)은 압축되면 AssetFileDescriptor 로 열 수 없다
        noCompress += "task"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.window:window:1.5.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.litert)
    implementation(libs.androidx.exifinterface)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

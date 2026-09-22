import java.security.MessageDigest

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
        applicationId = "com.example.trex_kotlin.v2"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "2.0.1-feedback-preview"
        manifestPlaceholders["applicationLabel"] = "trex_v2"
        val studio = project.hasProperty("validationStudio")
        manifestPlaceholders["allowBackup"] = (!studio).toString()
        buildConfigField("boolean", "VALIDATION_STUDIO", studio.toString())
        if (studio) {
            // MediaPipe 0.10.14에는 x86_64 JNI가 없으므로 지원되는 휴대폰 ABI로 고정한다.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            applicationId = "com.example.trex_kotlin.validation"
            versionCode = 1
            versionName = "1.0.0-validation-preview"
            manifestPlaceholders["applicationLabel"] = "TREX 검증"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(file("src/main/java"), rootProject.file("engine-lab/engine/src/main/kotlin"), file("src/main/assets/posture"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in listOf("kt", "json") }.toList() }
            .sortedBy { it.invariantSeparatorsPath }.forEach { source ->
                digest.update(source.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray())
                digest.update(source.readBytes())
            }
        buildConfigField("String", "SOURCE_FINGERPRINT", "\"${digest.digest().joinToString("") { "%02x".format(it) }}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 평가 설치는 사용자의 기존 앱·운동 기록과 분리한다.
            if (project.hasProperty("postureReplay")) {
                applicationIdSuffix = ".replay"
                versionNameSuffix = "-engine-eval"
                manifestPlaceholders["applicationLabel"] = "TREX 엔진 평가"
            }
        }
        release {
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
        buildConfig = true
    }
    androidResources {
        // MediaPipe 모델(.task)은 압축되면 AssetFileDescriptor 로 열 수 없다
        noCompress += "task"
        if (project.hasProperty("validationStudio")) {
            ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:exercise_gifs"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":pose-engine"))
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

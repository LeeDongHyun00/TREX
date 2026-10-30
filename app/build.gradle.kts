import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// 서버 주소·구글 웹 클라이언트 ID — local.properties(커밋 안 됨) > gradle 속성 > 기본값 순(docs/SERVER_SYNC_HANDOFF.md 2단계).
// 실기기 디버그는 adb reverse tcp:8080 tcp:8080 으로 PC 의 로컬 서버를 127.0.0.1 로 본다.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun trexConfig(name: String, default: String): String =
    localProps.getProperty(name) ?: (project.findProperty(name) as String?) ?: default

android {
    namespace = "com.example.trex_kotlin"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.trex_kotlin"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.3.0-preview.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 웹 클라이언트 ID 는 비밀값이 아니다(앱에 그대로 들어간다). 비어 있으면 구글 로그인 버튼이 안내 문구만 낸다.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${trexConfig("trex.googleWebClientId", "")}\"")
        // Android 의 Apple 로그인(웹 흐름) — Services ID·Return URL 도 비밀값이 아니다. 둘 중 하나라도 비면 Apple 버튼을 그리지 않는다.
        buildConfigField("String", "APPLE_SERVICES_ID", "\"${trexConfig("trex.appleServicesId", "")}\"")
        buildConfigField("String", "APPLE_REDIRECT_URI", "\"${trexConfig("trex.appleRedirectUri", "")}\"")
        // 실검증 도구(세트 로그·음성 기록·자가 라벨·횟수 검증 모드, 스펙 §116) — 저장소 코드로 빌드하면 켜져 있고, 사용자에게 나가는
        // 배포 빌드만 `-Ptrex.distribution=true` 로 끈다. 켜진 빌드는 버전 이름 끝에 "-dev" 가 붙어 세트 로그·배포 검사에서 갈린다.
        val distribution = trexConfig("trex.distribution", "false").toBoolean()
        buildConfigField("boolean", "DEV_TOOLS", (!distribution).toString())
        if (!distribution) versionNameSuffix = "-dev"
    }

    buildTypes {
        debug {
            buildConfigField("String", "TREX_API_BASE_URL", "\"${trexConfig("trex.apiBaseUrl.debug", "http://127.0.0.1:8080")}\"")
            // 로컬 서버(http) 확인용. 릴리스는 HTTPS 만 쓴다.
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            // 평가 설치는 사용자의 기존 앱·운동 기록과 분리한다.
            if (project.hasProperty("postureReplay")) applicationIdSuffix = ".replay"
        }
        release {
            buildConfigField("String", "TREX_API_BASE_URL", "\"${trexConfig("trex.apiBaseUrl", "")}\"")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            // 체험판은 기존 설치본과 같은 개발 인증서(debug 키스토어)로 서명한다 — 삭제 없이 업데이트되고 앱 내 업데이트(AppUpdate)가 서명을 대조한다.
            // 운영 배포용 별도 서명키는 아직 없다(docs/ANDROID_PREVIEW_RELEASE_1_3_0_7.md).
            // 이 인증서의 SHA-1 이 구글 콘솔 Android 클라이언트에 없으면 릴리스 APK 의 구글 로그인이 실패한다(docs/SERVER_SYNC_HANDOFF.md Q1).
            signingConfig = signingConfigs.getByName("debug")
            // R8 로 줄이고 최적화한다(스펙 §115) — Compose 는 R8 이 sourceInformation 호출을 지우고 람다·상태 코드를 인라인해야 제 속도가 난다.
            // 이름은 바꾸지 않는다(proguard-rules.pro 의 -dontobfuscate). 네이티브 라이브러리(MediaPipe·LiteRT)는 통째로 둔다.
            isMinifyEnabled = true
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
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.atomicfu)
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
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.androidx.browser)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// Android 정본 화면 파일을 사본 없이 그대로 컴파일한다(§108). 목록은 android-sources.txt 한 곳에만 있고,
// 빌드할 때마다 app/ 에서 다시 가져온다 — 커밋된 사본이 없으니 Android 와 어긋날 사본도 없다.
val androidSources = file("android-sources.txt").readLines()
    .map { it.substringBefore('#').trim() }
    .filter { it.isNotEmpty() }
val syncAndroidSources by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java")) { include(androidSources) }
    into(layout.buildDirectory.dir("android-sources"))
}

kotlin {
    // Compose Multiplatform 1.11 은 iosX64(인텔 시뮬레이터)를 내지 않는다 — 실기기·Apple Silicon 시뮬레이터만.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            // 엔진과 화면을 한 프레임워크로 묶는다. Kotlin/Native 런타임이 둘이면 객체를 서로 넘길 수 없다.
            baseName = "TrexCore"
            isStatic = true
            export(project(":posture-core"))
        }
    }
    sourceSets {
        commonMain {
            kotlin.srcDir(syncAndroidSources)
            dependencies {
                api(project(":posture-core"))
                implementation(libs.cmp.runtime)
                implementation(libs.cmp.foundation)
                implementation(libs.cmp.ui)
                implementation(libs.cmp.material3)
                implementation(libs.cmp.material.icons.extended)
            }
        }
    }
}

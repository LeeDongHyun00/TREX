// 앱 자세 엔진 소스를 복사하지 않고 그 자리에서 컴파일한다 — 재생 결과가 "현재 앱 카운터"의 결과여야 하기 때문이다.
// 안드로이드 의존이 없는 파일만 고른다. PostureAnalyzer.kt 의 추론 후처리는 MediaPipe/Android 타입에 묶여 있어
// Replay.kt 가 같은 절차를 옮겨 쓴다(파일 머리 주석 참고).
plugins {
    kotlin("jvm") version "2.3.21"
    application
}

repositories { mavenCentral() }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

val appRoot = rootDir.resolve("../../../app/src")
val postureMain = appRoot.resolve("main/java/com/example/trex_kotlin/posture")
val postureTest = appRoot.resolve("test/java/com/example/trex_kotlin/posture")
// PostureView.kt = ViewEstimator.frameFeatures — 앱 PostureAnalyzer 가 프레임 피처에 더하는 방향 피처(--dump-features 가 앱 피처 사전 전체를 낸다)
val engineFiles = listOf("PostureCore.kt", "RepCounter.kt", "ReturnRepTracker.kt", "RepHysteresis.kt", "PostureView.kt")

sourceSets {
    main {
        kotlin.srcDir(postureMain)
        kotlin.include(engineFiles + "trex/**")
    }
    test {
        // 앱 저장소의 카운터 유닛 테스트를 그대로 돌려, 여기서 컴파일한 카운터가 앱 테스트가 기대하는 그 카운터인지 확인한다.
        kotlin.srcDir(postureTest)
        // trex/** = 재생기 자체 테스트(src/test/kotlin — U 줄·--dump-features, spec §61)
        kotlin.include("RepCounterTest.kt", "ReturnRepTrackerTest.kt", "RepHysteresisTest.kt", "trex/**")
        resources.srcDir(appRoot.resolve("test/resources"))
        resources.include("rep_fixture_baseline1.txt")
    }
}

application { mainClass.set("trex.replay.ReplayKt") }

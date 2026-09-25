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
// RepForm.kt(반복별 자세 검사, spec §62a) + RuleTypes.kt(그것이 쓰는 RuleStatus·Verdict) — 규칙셋 연결(RepFormRules.kt)은 안드로이드 의존이라 뺀다
val engineFiles = listOf("PostureCore.kt", "RepCounter.kt", "ReturnRepTracker.kt", "RepHysteresis.kt", "PostureView.kt", "RepForm.kt", "RuleTypes.kt", "Stance2d.kt")

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

// installDist 가 끝날 때마다(최신이라 건너뛰어도) 표시 파일을 새로 쓴다 — gradle 은 내용이 같으면 jar 를 다시 쓰지 않아 jar 시각으로는
// '지금 소스로 빌드됐는가' 를 알 수 없다. run_replay.require_fresh_replay 가 이 파일 시각을 소스 시각과 견준다(빌드 #3: 옛 바이너리 사고).
val stampInstall by tasks.registering {
    outputs.upToDateWhen { false }
    doLast { layout.buildDirectory.file("install/trex-rep-replay/lib/.built").get().asFile.writeText("${System.currentTimeMillis()}\n") }
}
tasks.named("installDist") { finalizedBy(stampInstall) }

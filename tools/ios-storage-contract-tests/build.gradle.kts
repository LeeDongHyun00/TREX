plugins { kotlin("jvm") version "2.3.21" }

val repositoryRoot = projectDir.resolve("../..").canonicalFile
layout.buildDirectory = repositoryRoot.resolve("outputs/ios-aes256-review/contract-build")
kotlin { jvmToolchain(17) }
val productionSources by tasks.registering(Sync::class) {
    from(repositoryRoot.resolve("ios-ui/src/iosMain/kotlin")) {
        include("com/example/trex_kotlin/ios/IosSecureStorage.kt", "com/example/trex_kotlin/ios/UserDefaultsStore.kt")
    }
    from(repositoryRoot.resolve("app/src/main/java")) { include("com/example/trex_kotlin/KeyValueStore.kt") }
    into(layout.buildDirectory.dir("production-sources"))
}
kotlin.sourceSets.main { kotlin.srcDir(productionSources) }
dependencies {
    // 공통 모듈의 실제 JSON 구현. 먼저 루트 :posture-core:jvmJar를 빌드한다.
    implementation(files(repositoryRoot.resolve("posture-core/build/libs/posture-core-jvm.jar")))
    implementation("org.jetbrains.kotlinx:atomicfu:0.28.0")
    testImplementation("junit:junit:4.13.2")
}

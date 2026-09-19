import java.security.MessageDigest

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val engineDigest = MessageDigest.getInstance("SHA-256").run {
    rootProject.file("engine/src/main").walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(rootProject.projectDir).path }.forEach { update(it.readBytes()) }
    digest().joinToString("") { "%02x".format(it) }
}
val modelDigest = MessageDigest.getInstance("SHA-256").digest(file("src/main/assets/pose_landmarker_full.task").readBytes()).joinToString("") { "%02x".format(it) }
android {
    namespace = "com.trex.engine.lab"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.trex.engine.lab"
        minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "0.1.0-lab"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ENGINE_SHA256", "\"$engineDigest\"")
        buildConfigField("String", "MODEL_SHA256", "\"$modelDigest\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    androidResources { noCompress += "task" }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation(project(":engine"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

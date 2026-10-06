plugins { kotlin("multiplatform") }

kotlin {
    jvm()
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "TrexCore"
            isStatic = true
        }
    }
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation("junit:junit:4.13.2") }
        jvmTest { resources.srcDir("../app/src/test/resources") }
    }
}
tasks.withType<Test>().configureEach { workingDir = rootProject.file("app") }

package com.example.trex_kotlin.ios

import com.example.trex_kotlin.AppPlatform
import com.example.trex_kotlin.KeyValueStore
import com.example.trex_kotlin.MuscleLoadDatabase
import com.example.trex_kotlin.posture.SetLabelFormat
import com.example.trex_kotlin.posture.SetSelfLabel
import com.example.trex_kotlin.sync.BinaryTransport
import com.example.trex_kotlin.sync.HttpTransport
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.writeData
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * [AppPlatform] 의 iPhone 구현(§108 Phase 2). Android 판(AndroidPlatform.kt)과 같은 의미를 iOS API 로 잇는다.
 * 저장 자리: 키-값은 NSUserDefaults `trex.<이름>`, 근육 장부는 앱 지원 폴더 `TREX/muscle_load.json`,
 * 자가 라벨은 Documents `posture_logs/`(Android 의 앱 외부 폴더 자리 — Mac 이 devicectl 로 가져간다).
 */
open class IosAppPlatform : AppPlatform {
    override fun preferences(name: String): KeyValueStore = UserDefaultsStore(name)
    override val http: HttpTransport = UrlSessionTransport
    override val binary: BinaryTransport = UrlSessionBinaryTransport
    /** Android gradle.properties `trex.apiBaseUrl` 과 같은 서버. */
    override val apiBaseUrl: String = "https://trex-api.duckdns.org"
    /**
     * 서버가 확인하는 구글 웹 클라이언트 ID — Info.plist `GIDServerClientID`(Android gradle.properties `trex.googleWebClientId` 와 같은 값).
     * 비어 있으면 앱 상태가 "구글 로그인이 아직 준비되지 않았어요" 로 밝힌다. iPhone 클라이언트 ID 는 Swift 다리가 `GIDClientID` 로 읽는다.
     */
    override val googleWebClientId: String = (NSBundle.mainBundle.objectForInfoDictionaryKey("GIDServerClientID") as? String).orEmpty()
    /**
     * Apple 로그인(설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md) — Swift 다리 `AppleSignInBridge` 가 있으면 쓸 수 있다.
     * 다리 없이 띄운 화면(프로브 등)에서는 거짓 — 눌러도 안 되는 버튼을 그리지 않는다.
     */
    override val appleSignInAvailable: Boolean get() = IosAppleSignInRegistry.bridge != null
    /** project.yml `MARKETING_VERSION` — App Store 에 보이는 버전. */
    override val appVersion: String = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String).orEmpty()
    /** project.yml `CURRENT_PROJECT_VERSION` — 같은 버전을 다시 올리면 이것만 오른다. */
    override val appBuild: String = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String).orEmpty()
    override fun muscleLoadDatabase(): MuscleLoadDatabase = FileMuscleLoadDatabase(appSupportPath("TREX") + "/muscle_load.json")
    override fun appendSetLabel(label: SetSelfLabel) = SetLabelFiles(documentsPath() + "/posture_logs").append(label)
}

/** 자가 라벨 두 파일 — Android SetLabelStore 와 같은 자리 구조·같은 줄(SetLabelFormat). 파일 IO 는 메인 스레드 밖에서 한다. */
class SetLabelFiles(private val dir: String) {
    fun append(label: SetSelfLabel) {
        // Android 처럼 저장마다 백그라운드에서 쓰고, 실패는 삼킨다(라벨 파일은 연구용 사본이다 — 기록 자체는 앱 상태에 있다)
        scope.launch {
            runCatching {
                synchronized(lock) {
                    val labels = "$dir/${SetLabelFormat.LABELS_DIR}/${SetLabelFormat.LABELS_FILE}"
                    appendTextFile(labels, SetLabelFormat.encodeJson(label) + "\n")
                    val row = SetLabelFormat.truthRow(label) ?: return@synchronized
                    val truth = "$dir/${SetLabelFormat.TRUTH_FILE}"
                    // 헤더는 파일이 없거나 비어 있을 때만 — 중간에 헤더가 또 들어가면 csv.DictReader 가 데이터 행으로 읽는다
                    if (fileSize(truth) == 0L) appendTextFile(truth, SetLabelFormat.TRUTH_HEADER + "\n")
                    appendTextFile(truth, row + "\n")
                }
            }
        }
    }

    private companion object {
        val lock = SynchronizedObject()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

// ---- 파일 도우미(iOS)

internal fun documentsPath(): String =
    NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

/** 앱 지원 폴더 아래 [child] — 없으면 만든다. */
internal fun appSupportPath(child: String): String {
    val path = (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/" + child
    ensureDirectory(path)
    return path
}

@OptIn(ExperimentalForeignApi::class)
internal fun ensureDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
}

@OptIn(ExperimentalForeignApi::class)
internal fun fileSize(path: String): Long =
    (NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

internal fun readTextFile(path: String): String? = NSData.dataWithContentsOfFile(path)?.toByteArray()?.decodeToString()

/** 한 번에 쓴다(임시 파일 → 바꾸기). 실패하면 던진다. */
internal fun writeTextFile(path: String, text: String) {
    ensureDirectory(path.substringBeforeLast('/'))
    check(text.encodeToByteArray().toNSData().writeToFile(path, atomically = true)) { "파일을 쓰지 못했습니다: $path" }
}

/** 바이트를 한 번에 쓴다(임시 파일 → 바꾸기). 실패하면 던진다. */
internal fun writeBytesFile(path: String, bytes: ByteArray) {
    ensureDirectory(path.substringBeforeLast('/'))
    check(bytes.toNSData().writeToFile(path, atomically = true)) { "파일을 쓰지 못했습니다: $path" }
}

internal fun appendTextFile(path: String, text: String) {
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(path)) {
        ensureDirectory(path.substringBeforeLast('/'))
        check(manager.createFileAtPath(path, contents = null, attributes = null)) { "파일을 만들지 못했습니다: $path" }
    }
    val handle = checkNotNull(NSFileHandle.fileHandleForWritingAtPath(path)) { "파일을 열지 못했습니다: $path" }
    try {
        handle.seekToEndOfFile()
        handle.writeData(text.encodeToByteArray().toNSData())
    } finally {
        handle.closeFile()
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

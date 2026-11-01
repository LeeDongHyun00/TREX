package platform.Foundation

/** JVM에서는 메인 스레드 통지 계약만 대체한다. Apple CryptoKit/Keychain 검증을 대신하지 않는다. */
object NSThread { val isMainThread = true }

package com.example.trex_kotlin.food

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * [FoodMemory] 를 앱 전용 AES-256 파일(`files/food_memory.tsv.aes`)에 둔다. 기존 TSV는 검증 후 이관한다.
 * 사진 자체는 저장하지 않는다 — 이름과 특징값(숫자 384개)만 남는다. 비교도 폰에서만 한다.
 * 이 파일은 클라우드 자동 백업에서 뺐다(res/xml/backup_rules.xml·data_extraction_rules.xml). 암호키가 기기에 귀속되므로 기기 이전에서도 제외한다.
 */
object FoodMemoryStore {

    /** 기억할 자리 하나. [correctedFrom] 은 사용자가 모델 이름을 고쳤을 때 모델이 붙였던 이름. */
    data class Spot(val name: String, val photo: Bitmap, val box: PixelBox, val correctedFrom: String?)

    private const val TAG = "FoodMemory"
    private const val FILE_NAME = "food_memory.tsv"

    private val lock = Any()
    private var cached: FoodMemory? = null

    /** 기록 시트가 닫힌 뒤에도 저장이 끝나도록 화면 수명과 무관한 범위에서 돈다. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun load(context: Context): FoodMemory = synchronized(lock) {
        cached ?: run {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            val files = com.example.trex_kotlin.security.AndroidKeyCipher.files
            // 복호화 실패를 빈 기억으로 바꾸면 다음 저장에서 원본을 잃는다. 실패를 그대로 전달한다.
            val memory = if (files.exists(file)) FoodMemory.decode(files.readText(file)) else FoodMemory()
            memory.also { cached = it }
        }
    }

    /**
     * 사용자가 사진의 자리에 직접 붙인 이름을 기억한다. [spots] 는 이름·사진·사진 대비 0~1 자리·모델이 붙였던 이름(고친 경우).
     * 특징값 계산이 무거워 백그라운드에서 하고, 끝나면 파일에 한 번에 쓴다(중간에 앱이 죽어도 이전 파일이 남도록 임시 파일 → 이름 바꾸기).
     */
    fun rememberAsync(context: Context, spots: List<Spot>) {
        if (spots.isEmpty()) return
        val app = context.applicationContext
        scope.launch {
            val vectors = spots.mapNotNull { spot ->
                FoodDetector.embed(app, spot.photo, spot.box)?.let { spot to it }
            }
            if (vectors.isEmpty()) return@launch
            try {
                synchronized(lock) {
                    var memory = load(app)
                    val now = System.currentTimeMillis()
                    vectors.forEachIndexed { i, (spot, v) ->
                        memory = memory.plus(MemoryEntry(FoodMemory.clean(spot.name), v, now + i, spot.correctedFrom?.let(FoodMemory::clean)))
                    }
                    save(app, memory)
                    cached = memory
                }
                Log.i(TAG, "기억 ${vectors.size}개 추가")
            } catch (_: Exception) {
                // 실패한 저장은 메모리 캐시에도 반영하지 않는다. 이름·벡터·암호문을 로그에 남기지 않는다.
                Log.e(TAG, "음식 기억 저장 실패: 이전 기록을 유지합니다")
            }
        }
    }

    private fun save(context: Context, memory: FoodMemory) {
        com.example.trex_kotlin.security.AndroidKeyCipher.files.writeText(File(context.filesDir, FILE_NAME), memory.encode())
    }
}

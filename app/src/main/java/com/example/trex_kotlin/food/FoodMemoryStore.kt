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
 * [FoodMemory] 를 앱 전용 저장소 파일 하나(`files/food_memory.tsv`)에 둔다.
 * 사진 자체는 저장하지 않는다 — 이름과 특징값(숫자 384개)만 남는다. 비교도 폰에서만 한다.
 * 이 파일은 클라우드 자동 백업에서 뺐다(res/xml/backup_rules.xml·data_extraction_rules.xml). 새 폰으로 직접 옮기는 기기 이전에는 따라간다.
 */
object FoodMemoryStore {

    private const val TAG = "FoodMemory"
    private const val FILE_NAME = "food_memory.tsv"

    private val lock = Any()
    private var cached: FoodMemory? = null

    /** 기록 시트가 닫힌 뒤에도 저장이 끝나도록 화면 수명과 무관한 범위에서 돈다. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun load(context: Context): FoodMemory = synchronized(lock) {
        cached ?: run {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            val memory = try {
                if (file.exists()) FoodMemory.decode(file.readText(Charsets.UTF_8)) else FoodMemory()
            } catch (e: Exception) {
                Log.w(TAG, "기억 파일을 읽지 못해 빈 기억으로 시작한다", e)
                FoodMemory()
            }
            memory.also { cached = it }
        }
    }

    /**
     * 사용자가 사진의 자리에 직접 붙인 이름을 기억한다. [spots] 는 (이름, 사진, 사진 대비 0~1 자리).
     * 특징값 계산이 무거워 백그라운드에서 하고, 끝나면 파일에 한 번에 쓴다(중간에 앱이 죽어도 이전 파일이 남도록 임시 파일 → 이름 바꾸기).
     */
    fun rememberAsync(context: Context, spots: List<Triple<String, Bitmap, PixelBox>>) {
        if (spots.isEmpty()) return
        val app = context.applicationContext
        scope.launch {
            val vectors = spots.mapNotNull { (name, photo, box) ->
                FoodDetector.embed(app, photo, box)?.let { name to it }
            }
            if (vectors.isEmpty()) return@launch
            synchronized(lock) {
                var memory = load(app)
                val now = System.currentTimeMillis()
                vectors.forEachIndexed { i, (name, v) -> memory = memory.plus(MemoryEntry(FoodMemory.clean(name), v, now + i)) }
                save(app, memory)
                cached = memory
            }
            Log.i(TAG, "기억 ${vectors.size}개 추가: ${vectors.joinToString { it.first }}")
        }
    }

    private fun save(context: Context, memory: FoodMemory) {
        try {
            val dir = context.filesDir
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(memory.encode(), Charsets.UTF_8)
            if (!tmp.renameTo(File(dir, FILE_NAME))) {
                File(dir, FILE_NAME).writeText(memory.encode(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "기억 저장 실패", e)
        }
    }
}

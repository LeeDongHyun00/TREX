// Android 정본 동기화 — tools/sync_ios_core.py
package com.example.trex_kotlin.food

import kotlin.math.sqrt

/**
 * 내 음식 기억 — 사용자가 사진의 자리에 직접 붙인 이름을 그 자리 그림의 특징값과 함께 기억했다가,
 * 다음 사진에서 비슷한 자리를 만나면 그 이름을 먼저 내놓는다. 순수 계산부라 JVM 테스트로 검증한다.
 *
 * 왜 필요한가: 어떤 데이터셋으로 학습해도 없는 음식은 반드시 남는다(실사용 음식의 33% 가 AI Hub 에 없다, FOOD_EVAL §7).
 * 재학습 없이 폰 안에서만, 그 사람이 실제로 먹는 음식을 배운다.
 *
 * 특징값은 DINOv2-small(384차원, 정규화)이다. 342종 점수 벡터는 모르는 음식에서 1위 정확도 10% 라 못 쓰고,
 * ImageNet MobileNet 은 18% 였다. DINOv2 는 Food-101(식당이 다 다른 사진)에서 기억 10종·3장일 때
 * 1위 64%·3위 안 79%, AI Hub 에서 86%·94% 였다(FOOD_EVAL §9, `training/two_stage_eval/memory_eval.py`).
 */
/**
 * 기억 한 장. [correctedFrom] 은 사용자가 **모델이 붙인 이름을 고친** 기억일 때 모델이 원래 붙였던 이름이다(고침 기억).
 * 다음에 모델이 비슷한 자리에 같은 이름을 또 붙이면 사용자가 고친 이름으로 바꾸는 데 쓴다. "?" 에 붙인 기억이면 null.
 */
data class MemoryEntry(val name: String, val vector: FloatArray, val savedAt: Long, val correctedFrom: String? = null) {
    override fun equals(other: Any?): Boolean =
        other is MemoryEntry && other.name == name && other.savedAt == savedAt && other.correctedFrom == correctedFrom &&
            other.vector.contentEquals(vector)

    override fun hashCode(): Int = ((name.hashCode() * 31 + savedAt.hashCode()) * 31 + vector.contentHashCode()) * 31 + correctedFrom.hashCode()
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class FoodMemory(val entries: List<MemoryEntry> = emptyList()) {

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * [vector] 와 가장 비슷한 기억을 이름별 최고 유사도(코사인)로 줄 세운다. 상위 [limit] 개.
     * 한 이름에 여러 장이 있으면 그중 가장 비슷한 한 장으로 센다 — 같은 음식도 담긴 모양이 다르기 때문이다.
     */
    fun match(vector: FloatArray, limit: Int = 3, correctedFrom: String? = null): List<Pair<String, Float>> {
        val best = LinkedHashMap<String, Float>()
        for (e in entries) {
            // 모델이 이름을 붙인 자리는 "그 이름을 고친" 기억하고만 견준다 — 모델이 맞게 본 음식을 다른 기억이 덮지 않게.
            if (correctedFrom != null && e.correctedFrom != correctedFrom) continue
            val s = cosine(e.vector, vector)
            if (s > (best[e.name] ?: Float.NEGATIVE_INFINITY)) best[e.name] = s
        }
        return best.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    /**
     * 기억을 더한다. 이름마다 최근 [PER_NAME] 장, 전체 [TOTAL] 장까지만 남긴다 — 오래된 것부터 버린다.
     * 같은 음식이 계속 쌓여 다른 음식 기억을 밀어내지 않게, 그리고 파일이 끝없이 커지지 않게 한다.
     */
    fun plus(entry: MemoryEntry): FoodMemory {
        val sameName = entries.filter { it.name == entry.name }.sortedBy { it.savedAt }
        val dropSame = (sameName.size + 1 - PER_NAME).coerceAtLeast(0)
        val kept = entries - sameName.take(dropSame).toSet()
        val all = (kept + entry).sortedBy { it.savedAt }
        return FoodMemory(all.drop((all.size - TOTAL).coerceAtLeast(0)))
    }

    /** 이 이름의 기억을 모두 지운다. */
    fun forget(name: String): FoodMemory = FoodMemory(entries.filter { it.name != name })

    /** 모델이 [modelName] 을 붙였다가 사용자가 고친 기억이 있는가. 없으면 그 자리는 특징값을 뽑지 않는다(비용 절약). */
    fun hasCorrectionOf(modelName: String): Boolean = entries.any { it.correctedFrom == modelName }

    /**
     * 한 줄에 하나: `이름<TAB>저장시각<TAB>특징값(float32 리틀엔디언, Base64)[<TAB>모델이 붙였던 이름]`.
     * 넷째 칸은 고침 기억에만 있다(없으면 세 칸 — 먼저 만든 파일과 호환). 이름에 탭·줄바꿈은 저장 전에 공백으로 바꾼다.
     */
    fun encode(): String = entries.joinToString("\n") { e ->
        val bytes = ByteArray(e.vector.size * 4)
        e.vector.forEachIndexed { i, f -> val bits = f.toBits(); for (b in 0..3) bytes[i*4+b] = (bits ushr (8*b)).toByte() }
        "${clean(e.name)}\t${e.savedAt}\t${kotlin.io.encoding.Base64.encode(bytes)}" +
            (e.correctedFrom?.let { "\t${clean(it)}" } ?: "")
    }

    companion object {
        /**
         * 이 이상 비슷하면 이름을 자동으로 붙인다(사용자 결정 2026-09-27: "매우 비슷하면 자동, 애매하면 후보로만").
         * DINOv2 코사인 0.77 근처에서 AI Hub(기억 40종·3장) 자동 이름 정확도 80%(5개 중 1개는 틀린다)·모르는 음식 오붙임 5%·
         * 아는 음식 중 자동으로 붙는 비율 26%. 같은 질의 세트에서 고른 값이라 낙관적일 수 있다(§9 표). 자동으로 붙은 이름은
         * "기억" 표시로 모델 판정·직접 고름과 구분하고, 틀리면 눌러 바꾸면 된다(바꾼 이름이 새로 기억된다).
         */
        const val AUTO_NAME_AT = 0.77f

        /** 이 이상이면 음식 고르기 창의 "기억한 음식" 후보로 보인다. 틀린 후보는 고르지 않으면 그만이라 낮게 둔다. */
        const val SUGGEST_AT = 0.45f

        const val PER_NAME = 5
        const val TOTAL = 300

        fun decode(text: String): FoodMemory = FoodMemory(
            text.lineSequence().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size !in 3..4) return@mapNotNull null
                val bytes = runCatching { kotlin.io.encoding.Base64.decode(parts[2]) }.getOrNull() ?: return@mapNotNull null
                if (bytes.isEmpty() || bytes.size % 4 != 0) return@mapNotNull null
                val v = FloatArray(bytes.size / 4) { i -> Float.fromBits((0..3).fold(0) { bits, b -> bits or ((bytes[i*4+b].toInt() and 255) shl (8*b)) }) }
                MemoryEntry(parts[0], v, parts[1].toLongOrNull() ?: 0L, parts.getOrNull(3)?.takeIf { it.isNotEmpty() })
            }.toList(),
        )

        fun clean(name: String): String = name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

        /** 코사인 유사도. 길이가 다르면(모델이 바뀐 옛 기억) 비교하지 않는다. */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return Float.NEGATIVE_INFINITY
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            return dot / (sqrt(na) * sqrt(nb) + 1e-9f)
        }
    }
}

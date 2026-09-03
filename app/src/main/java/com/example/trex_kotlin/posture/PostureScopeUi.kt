package com.example.trex_kotlin.posture

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 규칙셋을 한 번만 읽어 두는 캐시 (spec §31).
 *
 * 평가 범위([PostureScope])는 세션 밖에서도 필요하다 — 운동 카드가 "이 종목에서 무엇을 보고 무엇을 못 보는지"를
 * 자세 교정을 켜기 **전에** 말해야 하기 때문이다. 세션 화면들은 각자 로드하지만 카드는 목록에 여러 개가 뜨므로
 * 종목마다 에셋을 다시 파싱하면 안 된다. 서서 하는 종목(mp_v0)과 바닥 종목(floor_v0)을 합쳐 둔다 —
 * 바닥 종목은 규칙이 전부 floor 쪽에 있어 합치지 않으면 범위가 통째로 비어 보인다.
 */
object PostureScopeCache {

    @Volatile
    private var merged: PostureRuleSet? = null

    /** 이미 읽었으면 그대로, 아니면 에셋을 읽어 합친다. 실패하면 null (호출부는 범위 표시를 생략한다). */
    fun load(context: Context): PostureRuleSet? {
        merged?.let { return it }
        val loaded = runCatching {
            val standing = PostureRuleSet.load(context)
            try {
                val floor = PostureRuleSet.load(context, FLOOR_RULES_ASSET)
                PostureRuleSet("${standing.version}+${floor.version}", standing.generated, standing.rules + floor.rules)
            } catch (_: Throwable) {
                standing
            }
        }.getOrNull()
        if (loaded != null) merged = loaded
        return loaded
    }
}

/**
 * 종목의 평가 범위를 비동기로 읽어 온다. [exercise] 는 AIHub 종목명(앱 이름이 아니라 `postureExerciseMap` 의 값).
 * 아직 안 읽혔거나 규칙셋 로드에 실패하면 null — 호출부는 범위 줄을 그냥 숨긴다(모르는 것을 지어내지 않는다).
 */
@Composable
fun rememberPostureScope(exercise: String?): PostureScope? {
    val context = LocalContext.current
    var scope by remember(exercise) { mutableStateOf<PostureScope?>(null) }
    LaunchedEffect(exercise) {
        if (exercise == null) {
            scope = null
            return@LaunchedEffect
        }
        // 에셋 파싱은 IO 로 — 카드가 여러 개 뜨는 목록에서 메인 스레드를 잡으면 스크롤이 끊긴다
        scope = withContext(Dispatchers.IO) {
            PostureScopeCache.load(context)?.let { PostureScope.of(it, exercise) }
        }
    }
    return scope
}

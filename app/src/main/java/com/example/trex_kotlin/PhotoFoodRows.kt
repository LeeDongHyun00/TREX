package com.example.trex_kotlin

import com.example.trex_kotlin.food.FoodMemory
import com.example.trex_kotlin.food.FoodMemoryPolicy
import com.example.trex_kotlin.food.FoodRegion

/*
 * 사진 식단 결과 줄과 그 연산 — 화면(PhotoFoodSheet)에서 떼어 JVM 테스트(`PhotoFoodRowsTest`)로 고정한다.
 * 줄이 합쳐지고 나뉠 때 사용자가 직접 고른 자리가 그 자리만 따라가야 사진 이름표·목록 표시·내 음식 기억이 같은 말을 한다.
 */

/**
 * 결과 한 줄이 어디서 왔는지.
 *
 * 확신도와 출처 사진을 각각 nullable 로 두면 한쪽만 채워진 상태가 만들어져 화면에서 말이
 * 엇갈린다("확신 62%"와 "직접 고름"이 같이 뜨는 식). 타입으로 묶어 그걸 막는다.
 */
internal sealed interface FoodSource {
    /** 모델이 [photoIndex] 번째 사진에서 [confidence] 로 잡았다. */
    data class Detected(val confidence: Float, val photoIndex: Int) : FoodSource

    /**
     * 사용자가 직접 골랐다. 확신도도 출처 사진도 없다 —
     * 판정하지 않은 것을 판정한 것처럼 말하지 않고, 아무 사진이나 붙여 거기서 잡힌 것처럼 보이게 하지 않는다.
     */
    data object Picked : FoodSource

    /**
     * 내 음식 기억이 이 자리를 전에 사용자가 고른 음식과 매우 비슷하다고 봤다([FoodMemory.AUTO_NAME_AT] 이상).
     * 모델 판정도 이번의 직접 선택도 아니라 따로 둔다 — 화면에는 "기억한 음식" 으로 밝힌다.
     */
    data class Remembered(val similarity: Float, val photoIndex: Int) : FoodSource
}

/**
 * 사진 위 자리의 이름표. [kind] 는 그 이름이 어디서 왔는지 — 사용자가 붙이거나 바꾼 이름을 모델 이름과
 * 사진 위에서 같은 모양으로 그리면, 판정하지 않은 것을 판정한 것처럼 보인다(목록의 "직접 고름" 과 같은 이유).
 */
internal data class RegionLabel(val name: String, val kind: LabelKind)

/** 자리 이름표가 어디서 왔는지. 사진 위에서 모양으로 구분한다. */
internal enum class LabelKind { MODEL, MEMORY, USER }

/**
 * 결과 한 줄. 인분은 직접 기록 시트와 같은 스테퍼(0.5 씩, 숫자를 누르면 직접 입력 — [FoodPortion])로 조절한다. nutrition 이 없으면 기록에서 제외한다.
 *
 * [regionIds] 는 이 줄이 사진의 어느 자리([FoodRegion.id])에서 왔는지 — 사진 위 이름표가 이것으로 정해진다.
 * 자리의 이름표를 따로 들고 있지 않는 이유: 줄을 바꾸거나 지웠을 때 사진과 목록이 어긋나지 않게, 줄 하나만 정본으로 둔다.
 * 줄이 지워지면 그 자리는 다시 "?" 가 되어 이름을 새로 붙일 수 있다.
 *
 * [userRegionIds] 는 [regionIds] 중 사용자가 이 줄 이름을 **직접 고른** 자리 — 내 음식 기억은 이 자리만 남긴다([FoodMemoryPolicy]).
 * 줄의 [source] 는 합쳐질 때 앞 줄 것을 따르므로, 그것으로 짐작하면 같은 이름으로 합쳐진 다른 자리까지 사용자 선택이 된다.
 */
internal data class RecognizedItem(
    val name: String,
    val nutrition: Nutrition?,
    val source: FoodSource,
    val qty: Double = 1.0,
    val regionIds: Set<Int> = emptySet(),
    val userRegionIds: Set<Int> = emptySet(),
)

/**
 * 같은 이름을 한 줄로 합친다. 저장 경로의 [AppViewModel.appendFoods] 와 같은 규칙이라,
 * 저장 전 화면과 저장된 기록이 어긋나지 않는다.
 *
 * 합치지 않으면 "쌀밥 1 / 쌀밥 1" 두 줄이 생겨, 한 줄을 지워 뺐다고 생각해도 다른 줄이 남는다.
 *
 * "?" 자리에 이미 있는 음식을 고르면 그 줄로 합쳐져 수량이 +1 된다. 그 줄의 출처(확신도 표시)는 앞 줄 것을 따르므로
 * 모델이 잡은 줄에 사용자가 더한 한 그릇이 섞일 수 있다 — 사진 위에서는 자리마다 [labelKindFor] 로 구분된다.
 *
 * 영양값은 없을 때만 뒤에서 채운다. 직접 등록으로 방금 적은 값이 버려지는 것을 막으면서도,
 * 이미 있는 줄의 값을 조용히 바꾸지는 않는다.
 */
internal fun List<RecognizedItem>.mergedByName(): List<RecognizedItem> =
    fold(emptyList()) { acc, item ->
        val at = acc.indexOfFirst { it.name == item.name }
        if (at < 0) {
            acc + item
        } else {
            acc.mapIndexed { i, kept ->
                if (i == at) {
                    kept.copy(
                        qty = FoodPortion.add(kept.qty, item.qty), nutrition = kept.nutrition ?: item.nutrition,
                        regionIds = kept.regionIds + item.regionIds, userRegionIds = kept.userRegionIds + item.userRegionIds,
                    )
                } else {
                    kept
                }
            }
        }
    }

internal fun List<RecognizedItem>.toEntries(): List<FoodEntry> =
    mapNotNull { item -> item.nutrition?.let { FoodEntry(item.name, it, item.qty) } }

/** 사용자가 고른 음식 한 줄. [regionId] 는 이름을 붙인 사진의 "?" 자리, 자리 없이 빠진 음식을 더하면 null. */
internal fun List<RecognizedItem>.withPicked(name: String, nutrition: Nutrition?, regionId: Int?): List<RecognizedItem> {
    val ids = regionId?.let { setOf(it) }.orEmpty()
    return (this + RecognizedItem(name, nutrition, FoodSource.Picked, regionIds = ids, userRegionIds = ids)).mergedByName()
}

/**
 * [index] 번째 줄을 사용자가 고른 [name] 으로 바꾼다. 사진에서 누른 자리 [regionId] 가 여러 자리를 묶은 줄의 하나면 그 자리만 떼어
 * 새 줄로 만든다 — 두 그릇 중 하나만 틀렸을 수 있다. 아니면 줄 전체를 바꾸고 그 줄의 자리 모두를 사용자가 고른 것으로 친다.
 * 바꿔 넣은 이름은 모델이 판정한 게 아니라 판정 흔적(확신도)을 떼어 낸다.
 */
internal fun List<RecognizedItem>.withReplaced(index: Int, name: String, nutrition: Nutrition?, regionId: Int?): List<RecognizedItem> {
    val row = getOrNull(index) ?: return this
    val picked = RecognizedItem(name, nutrition, FoodSource.Picked)
    return if (regionId != null && regionId in row.regionIds && row.regionIds.size > 1) {
        mapIndexed { i, it -> if (i == index) it.copy(regionIds = it.regionIds - regionId, userRegionIds = it.userRegionIds - regionId) else it } +
            picked.copy(regionIds = setOf(regionId), userRegionIds = setOf(regionId))
    } else {
        mapIndexed { i, it -> if (i == index) picked.copy(qty = it.qty, regionIds = it.regionIds, userRegionIds = it.regionIds) else it }
    }.mergedByName()
}

/**
 * 이 줄에 든 [region] 의 이름표 종류. 사용자가 이 자리에 직접 고른 이름은 기억 이름과 같아도 사용자 선택이다 — 기억이 붙인 줄을
 * 지운 뒤 "?" 에 같은 이름을 직접 고른 경우가 그렇다. 같은 이름 줄에 합쳐진 다른 자리는 기억 이름 그대로다.
 */
internal fun RecognizedItem.labelKindFor(region: FoodRegion): LabelKind = when {
    name == region.name -> LabelKind.MODEL
    name == region.rememberedName && region.id !in userRegionIds -> LabelKind.MEMORY
    else -> LabelKind.USER
}

/** 이 줄에 기억이 이름을 붙인 자리가 섞였는가 — 목록에서 "기억 포함" 으로 밝힌다(사진의 연두 이름표와 같은 말). */
internal fun RecognizedItem.includesMemorySpot(regions: List<FoodRegion>): Boolean =
    regions.any { it.id in regionIds && labelKindFor(it) == LabelKind.MEMORY }

/** 내 음식 기억 규칙([FoodMemoryPolicy])이 보는 줄 — 기록되는 줄(영양값이 있는 줄)인지와 사용자가 직접 고른 자리. */
internal fun List<RecognizedItem>.memoryRows(): List<FoodMemoryPolicy.Row> =
    map { FoodMemoryPolicy.Row(it.name, recordable = it.nutrition != null, userRegionIds = it.userRegionIds) }

package com.trex.engine

import kotlin.math.roundToInt

/** 같은 촬영 구간·같은 측·같은 신호의 완료 반복끼리 비교한다. 초반은 관측값이며 정답이 아니다. */
internal class ObservedRangeComparison {
    private val initial=mutableMapOf<String,MutableList<Float>>()
    private val recent=mutableMapOf<String,MutableList<Float>>()
    fun reset() { initial.clear();recent.clear() }
    fun add(events:List<ExerciseRepEvent>):String? {
        var message:String?=null
        for(event in events) for((side,cycle) in event.cycles) {
            val key="${side.name}:${cycle.signalFeature}"
            val amplitude=cycle.max-cycle.min
            val baseline=initial.getOrPut(key){mutableListOf()}
            if(baseline.size<3) {baseline+=amplitude;continue}
            val window=recent.getOrPut(key){mutableListOf()}
            window+=amplitude;if(window.size>3)window.removeAt(0)
            if(window.size<3)continue
            val before=baseline.sorted()[1];val now=window.sorted()[1]
            if(before<=0)continue
            val change=((now-before)/before*100).roundToInt()
            if(kotlin.math.abs(change)>=20) {
                val label=when(side){RepSide.LEFT->"왼쪽 ";RepSide.RIGHT->"오른쪽 ";else->""}
                message="${label}최근 3회 왕복 범위가 초반 3회보다 ${kotlin.math.abs(change)}% ${if(change>0) "커졌어요" else "작아졌어요"} · 관측 비교"
            }
        }
        return message
    }
}

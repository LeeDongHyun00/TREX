package com.example.trex_kotlin.posture

data class MotionReplayResult(val events: List<MotionCompletion>, val mismatchedFrameTimes: List<Long>)

/** 앱과 같은 카운터를 재사용하는 재생 하니스. 별도 Python 계수 구현을 정답처럼 비교하지 않는다. */
object MotionReplay {
    fun run(exercise: String, version: String, frames: List<MotionTraceFrame>, rules: List<PostureRule> = emptyList()): MotionReplayResult {
        require(version == MotionContracts.VERSION) { "다른 엔진 버전: $version" }
        val counter=MotionRepCounter.forExercise(exercise,rules)
        val events=ArrayList<MotionCompletion>(); val mismatches=ArrayList<Long>()
        for(frame in frames) {
            if(frame.phase==MotionPhase.PAUSED.name) counter?.resetCycle()
            val found=counter?.onFrame(frame.tMs,frame.features).orEmpty()
            if(found.size!=frame.completed) mismatches+=frame.tMs
            events+=found
        }
        return MotionReplayResult(events,mismatches)
    }
}

package com.example.trex_kotlin.posture

/**
 * 촬영 전환 전에 시작한 추론이 새 측정 구간에 들어오지 않게 한다.
 * 추론은 락 밖에서 실행하고 결과 반영과 경계 초기화만 같은 락으로 직렬화한다.
 * 번호는 관측 조건의 경계이며 자세의 정상 여부를 의미하지 않는다.
 */
class ObservationEpoch {
    private var version = 0L

    @Synchronized fun ticket(): Long = version

    /** 세트 마감도 결과 반영 중간에 들어가 일부만 저장하지 않도록 같은 경계를 사용한다. */
    @Synchronized fun <T> exclusive(action: () -> T): T = action()

    @Synchronized fun invalidate(reset: () -> Unit = {}) {
        version++
        reset()
    }

    @Synchronized fun applyIfCurrent(ticket: Long, consume: () -> Unit): Boolean {
        if (ticket != version) return false
        consume()
        return true
    }
}

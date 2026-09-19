package com.trex.engine

/** 새 운동 선택의 유일한 목록. 별칭은 불러오기와 검색에서만 사용한다. */
object ExerciseCatalog {
    val profiles: List<ExerciseRepProfile> get() = ExerciseRepProfiles.all.map { ExerciseRepProfiles.forExercise(it.exercise)!! }
    fun canonical(name: String): String? = ExerciseRepProfiles.forExercise(name)?.exercise
    fun category(name: String): String = when (canonical(name)) {
        "바벨 스쿼트", "스텝 포워드 다이나믹 런지", "바벨 런지", "사이드 런지", "크로스 런지", "바벨 데드리프트", "굿모닝", "힙쓰러스트" -> "하체"
        "플랭크" -> "코어"
        "스탠딩 사이드 크런치", "스탠딩 니업", "행잉 레그 레이즈", "크런치", "라잉 레그 레이즈", "시저크로스" -> "복근"
        else -> "상체"
    }
}

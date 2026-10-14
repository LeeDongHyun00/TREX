package com.example.trex_kotlin.posture

/** 측면 푸시업 반복 검사. 검출 가능 조건과 자세 조건을 분리한다. */
object PushupFormSpecs {
    const val SUPPORT = "pushup_knee_support"

    /** 무릎 각도 하나로 접지를 단정하지 않는다. 같은 프레임·같은 쪽의 지지선 높이도 필요하다. */
    fun withSupport(features: Map<String, Float>): Map<String, Float> = buildMap {
        putAll(features.filterKeys { !it.startsWith("${SUPPORT}_") })
        if (features["pushup_side_ready"] != 1f) return@buildMap
        for (side in listOf("L", "R")) {
            val knee = features["pushup_knee_angle_$side"]?.takeIf { it.isFinite() } ?: continue
            val clearance = features["pushup_knee_clearance_$side"]?.takeIf { it.isFinite() } ?: continue
            put("${SUPPORT}_$side", if (knee <= 130f && clearance <= .10f) 1f else 0f)
        }
    }

    fun checks(): List<RepFormCheck> {
        val ex = "푸시업"
        val caution = listOf("사용자 요청에 따라 코치 모드에 적용한 검증 중 기준이다. 실제 휴대폰의 오탐·미탐은 추가 확인이 필요하다",
            "관절이나 얼굴이 보이지 않으면 판정을 유보한다. 눈동자 시선·척추 곡률·실제 바닥 접촉은 직접 측정하지 않는다")
        fun check(name: String, feature: String, part: String, lo: Float?, hi: Float?, low: String?, high: String?,
                  fix: String, reason: String, highlight: String, unit: String = "", samples: Int = 5,
                  coverage: Float = .75f, gates:Boolean=true, views: Set<String> = setOf("SIDE_B", "SIDE_D")) =
            RepFormCheck("repform|$ex|$name", ex, "$name(반복)", part, RuleStatus.BETA,
                feature, RepPhase.CYCLE, RepFormStat.MEDIAN, RepFormRef.NONE, lo, hi, low, high,
                if (lo == null) null else "부족", if (hi == null) null else "벗어남", fix, reason,
                unit = unit, cautions = caution, views = views, gates = gates, cue = name,
                highlight = highlight, minSamples = samples, minCoverage = coverage,
                requireObservedView = false, maxObservationGapMs = 750L, strictObservationWindow = true)
        return buildList {
            // 일반 푸시업에서 무릎을 대는 동작을 먼저 안내한다. 니푸쉬업에는 등록하지 않는다.
            for ((side, label) in listOf("L" to "왼쪽", "R" to "오른쪽")) add(check(
                "$label 무릎 지지", "${SUPPORT}_$side", "무릎", null, .5f, null,
                "무릎이 굽혀진 채 바닥 가까이 내려가 있어요",
                "무릎을 바닥에서 떼고 다리를 편 상태로 반복해 주세요",
                "동측 무릎각 ≤130°와 손목-발끝 지지선까지의 무릎 높이/몸통 길이 ≤0.10이 반복 관측의 과반에서 함께 충족. 접촉 라벨이 없는 자료의 분포를 확인한 초기값이다",
                "knee_ang_$side"))
            add(check("얼굴 카메라 방향", "pushup_face_yaw", "고개", null, 65f, null,
                "얼굴이 옆의 카메라를 향하고 있어요", "얼굴을 바닥으로 향하고 목을 몸통과 나란히 유지해 주세요",
                "기존 바닥 운동처럼 얼굴 메시 회전 행렬을 사용한다. 몸 측면이 확인된 프레임만 사용하며 +65° 초과를 카메라 방향의 초기 기준으로 둔다. 미검출은 정상으로 보지 않는다",
                "head_trunk_ang", "°", samples = 3, coverage = .4f))
            add(check("손 간격", "pushup_hand_width", "손목", .55f, 2.2f,
                "손 간격이 어깨에 비해 많이 좁아요", "손 간격이 어깨에 비해 많이 넓어요",
                "손을 어깨 너비보다 약간 넓게 놓아 주세요",
                "분리된 양어깨 축에 투영한 손목 간격/어깨 간격. 관측 가능한 정면·사선에서만 생성한다. 0.55~2.2는 실기기 검증 전의 보수적 초기 띠다",
                "wrist_shoulder_d", views = setOf("B", "C", "D")))
            for ((side, label) in listOf("L" to "왼쪽", "R" to "오른쪽")) {
                add(check("$label 팔꿈치 벌어짐", "pushup_elbow_flare_$side", "팔꿈치", 10f, 85f,
                    "팔꿈치가 몸통에 너무 붙어 있어요", "팔꿈치가 몸통에서 많이 벌어져 있어요",
                    "팔꿈치를 몸통에서 너무 멀리 벌리지 말고 편하게 내려가 주세요",
                    "사용자 사진의 45도는 팔꿈치 굽힘각이 아니라 몸통-위팔 벌어짐이다. 양어깨와 몸통 축이 분리되는 시점의 2D 각도만 사용한다. 10~85도 밖에서만 안내하고 정확히 45도를 요구하지 않는다",
                    "elbow_ang_$side", "°", samples=2, gates=false, views=setOf("B","C","D")).copy(phase=RepPhase.BOTTOM))
                add(check("$label 손 앞뒤 위치", "pushup_hand_along_torso_$side", "손목", -.30f, 1f,
                    "손이 몸통에 비해 머리 쪽에 있어요", "손이 몸통에 비해 허리 쪽에 있어요",
                    "내려갔을 때 손이 가슴 아래쪽 옆에 오도록 놓아 주세요",
                    "어깨에서 손목까지를 어깨-골반 축에 투영한 비율. 가슴 하부 점 자체를 추정하지 않으며 -0.30~1.0은 검증 전 여유 띠로 안내만 한다",
                    "wrist_shoulder_d", samples=2,gates=false).copy(phase=RepPhase.BOTTOM))
                add(check("$label 골반 정렬", "pushup_hip_offset_$side", "골반", -.12f, .20f,
                    "엉덩이가 어깨와 발목을 잇는 선보다 내려갔어요", "엉덩이가 어깨와 발목을 잇는 선보다 올라갔어요",
                    "배에 힘을 주고 어깨부터 발목까지 일직선을 유지해 주세요",
                    "동측 어깨-발목 선에서 골반의 부호 있는 거리/어깨-발목 길이. 작은 자연스러운 차이를 허용하는 -0.12~0.20 여유 띠이며 기기 검증 전이다",
                    "hip_dev_ankle_$side"))
                add(check("$label 고개 방향", "pushup_head_pitch_$side", "고개", -55f, 40f,
                    "고개가 몸통보다 많이 숙여졌어요", "고개가 몸통보다 많이 들렸어요",
                    "턱을 당기거나 들지 말고 목을 몸통과 나란히 유지해 주세요",
                    "동측 귀-코 방향과 몸통 방향의 상대 각도. -55~40° 밖에서만 안내하며 얼굴의 카메라 회전 검사와 구분한다",
                    "head_trunk_ang_$side", "°",gates=false))
                add(check("$label 목 정렬", "pushup_neck_pitch_$side", "목", -85f, 50f,
                    "머리와 목이 몸통보다 많이 내려갔어요", "머리와 목이 몸통보다 많이 올라갔어요",
                    "머리를 몸통의 연장선에 맞춰 주세요", "동측 어깨-귀 방향과 몸통 방향의 상대 각도. -85~50° 밖에서만 안내한다",
                    "head_trunk_ang_$side", "°",gates=false))
            }
        }
    }
}

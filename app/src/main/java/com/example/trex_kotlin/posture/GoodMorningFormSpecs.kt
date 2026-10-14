package com.example.trex_kotlin.posture

/** 굿모닝 검사. 상태는 BETA로 유지하며 반복 판정과 코칭 정책을 분리한다. */
object GoodMorningFormSpecs {
    fun checks(): List<RepFormCheck> {
        val ex = "굿모닝"
        val caution = listOf("실기기 반복 판정 정확도는 검증 중이다", "몸통 숙임은 사진을 참고한 초기 범위이며 척추 곡률과 손가락 깍지는 평가하지 않는다")
        val oblique = RepFormCheck("repform|$ex|무릎 펴짐", ex, "보이는 무릎 굽힘(반복)", "무릎", RuleStatus.BETA,
            GoodMorningGeometry.OBLIQUE_KNEE, RepPhase.BOTTOM, RepFormStat.MEDIAN, RepFormRef.NONE,
            lo = null, hi = 175f, lowText = null, highText = "몸통을 숙인 구간에서 왼무릎이 많이 펴져 있어요",
            lowLabel = null, highLabel = "펴짐", fix = "무릎을 살짝 구부린 상태로 엉덩이를 뒤로 보내 주세요",
            reason = "사용자 요청으로 175° 초과에서만 무릎 굽힘을 안내한다. 완전히 펴지는 모습만 참고하며 횟수는 제외하지 않는다",
            unit = "°", cautions = caution + "오른쪽 사선과 가려진 반대쪽 무릎은 이 검사 범위가 아니다",
            views = setOf("D", "E"), gates = false, cue = "무릎 굽힘", highlight = "knee_L",
            minSamples = 2, minCoverage = .75f, requireObservedView = true, maxObservationGapMs = 750L, strictObservationWindow = true)
        val side = listOf("L" to "왼", "R" to "오른").map { (key, name) ->
            RepFormCheck("repform|$ex|${name}무릎 측면 굽힘", ex, "${name}무릎 측면 굽힘(반복)", "무릎", RuleStatus.BETA,
                "${GoodMorningGeometry.SIDE_KNEE}_$key", RepPhase.BOTTOM, RepFormStat.MEDIAN, RepFormRef.NONE,
                lo = 135f, hi = null, lowText = "몸통을 숙인 구간에서 ${name}무릎이 많이 굽혀졌어요",
                highText = null, lowLabel = "많이 굽힘", highLabel = null,
                fix = "무릎을 살짝 구부린 상태로 엉덩이를 뒤로 보내 주세요",
                reason = "측면 투영이 확인된 동측 엉덩이-무릎-발목 2D 각도. 135° 미만의 큰 무릎 굽힘과 같은 쪽 골반 하강이 함께 확인된 반복만 제외한다. 정확한 무릎 굽힘각이나 엉덩이의 시계 방향은 요구하지 않는다. 실기기 검증 전 초기값이다",
                unit = "°", cautions = caution + "2D 측면 투영이며 무릎 통증·안전성·반동 여부는 판단하지 않는다",
                views = setOf(if (key == "L") "SIDE_D" else "SIDE_B", if(key=="L") "E" else "A"), gates = true, cue = "무릎 굽힘", highlight = "knee_$key",
                minSamples = 2, minCoverage = .75f, requireObservedView = false, maxObservationGapMs = 750L, strictObservationWindow = true)
        }
        val shoulder = RepFormCheck("repform|$ex|어깨 높이 흔들림", ex, "어깨 높이 흔들림(반복)", "어깨", RuleStatus.BETA,
            GoodMorningGeometry.SHOULDER_SWAY, RepPhase.CYCLE, RepFormStat.STD, RepFormRef.NONE,
            lo = null, hi = .10f, lowText = null, highText = "반복 중 양쪽 어깨의 높이 차이가 달라졌어요",
            lowLabel = null, highLabel = "흔들림", fix = "양쪽 어깨 높이를 유지하며 몸통을 숙여 주세요",
            reason = "어깨 높이 차이의 표준편차 0.10을 넘는 큰 흔들림만 안내한다. 척추 중립을 직접 측정하지 않는다",
            cautions = caution + "처음부터 일정하게 유지한 어깨 기울기는 평가하지 않는다", views = setOf("C"), gates = false,
            cue = "어깨 높이", highlight = "shoulder_asym", minSamples = 5, minCoverage = .75f,
            requireObservedView = true, maxObservationGapMs = 750L, strictObservationWindow = true)
        fun extra(name:String, feature:String, part:String, phase:RepPhase, lo:Float?, hi:Float?, low:String?, high:String?,
                  fix:String, reason:String, highlight:String, views:Set<String>, ref:RepFormRef=RepFormRef.NONE, gates:Boolean=false) =
            RepFormCheck("repform|$ex|$name",ex,"$name(반복)",part,RuleStatus.BETA,feature,phase,RepFormStat.MEDIAN,ref,
                lo,hi,low,high,if(lo==null)null else "부족",if(hi==null)null else "벗어남",fix,reason,
                cautions=caution,views=views,gates=gates,cue=name,highlight=highlight,minSamples=2,minCoverage=.75f,
                minReferenceSamples=2,requireObservedView=true,maxObservationGapMs=750L,strictObservationWindow=true)
        val photoChecks=buildList {
            add(extra("발 간격","goodmorning_stance","발",RepPhase.TOP,.45f,2.2f,
                "발 간격이 골반에 비해 많이 좁아요","발 간격이 골반에 비해 많이 넓어요",
                "발을 골반 너비로 놓아 주세요","사용자 사진의 골반 너비 자세. 정면 발목 간격/골반 간격이며 0.45~2.2 밖에서만 안내한다. 발 간격만으로 횟수를 제외하지 않는다",
                "stance_sh",setOf("C")))
            for((key,label) in listOf("L" to "왼","R" to "오른")) {
                add(extra("${label}손 머리 뒤 위치","goodmorning_hand_head_$key","손",RepPhase.CYCLE,null,.90f,
                    null,"${label}손이 머리에서 많이 떨어져 있어요","양손을 머리 뒤쪽에 편하게 모아 주세요",
                    "동측 손목-귀 거리/몸통 길이. 0.90의 넓은 여유를 두고 반복 내내 머리에서 멀리 떨어진 경우만 제외한다. 손가락 깍지나 실제 접촉은 판정하지 않으며 가려진 손은 유보한다",
                    "elbow_$key",ViewEstimator.ViewClass.entries.map { it.letter }.toSet(),gates=true)
                    .copy(minSamples=5,requireObservedView=false))
                add(extra("${label}쪽 상체 숙임","goodmorning_torso_incl_$key","상체",RepPhase.BOTTOM,35f,95f,
                    "상체가 충분히 숙여지지 않았어요","상체가 아래로 많이 내려갔어요",
                    "무릎은 가볍게 굽히고 상체를 편하게 숙여 주세요",
                    "측면에서 수직 대비 어깨-골반 각도. 사진의 숙임을 참고한 35~95° 초기 여유 띠로 정확한 각도를 요구하지 않는다. 가려짐·정면 투영은 유보한다",
                    "torso_incl",setOf(if(key=="L")"SIDE_D" else "SIDE_B",if(key=="L")"E" else "A"),gates=true)
                    .copy(requireObservedView=false))
                add(extra("${label}발끝 방향","goodmorning_toe_$key","발",RepPhase.TOP,-35f,35f,
                    "${label}발끝이 안쪽으로 많이 돌아갔어요","${label}발끝이 바깥으로 많이 돌아갔어요",
                    "발끝이 나란히 앞을 향하게 놓아 주세요","사용자 사진의 11자 발끝. 기존 스쿼트와 같은 이미지 발목-발끝 측정 구조를 사용하되 여유 띠 ±35°를 사용한다. 정확한 0°를 요구하지 않고 크게 돌아간 발끝만 제외한다",
                    "toe2d_$key",setOf("C"),gates=true))
                add(extra("${label}무릎 안쪽 모임","goodmorning_knee_out_$key","무릎",RepPhase.BOTTOM,-.35f,null,
                    "${label}무릎이 안쪽으로 모였어요",null,"무릎이 발끝 방향을 따라가도록 유지해 주세요",
                    "정면 골반-발목 선에 대한 무릎의 가로 이탈/골반 너비. -0.35는 큰 모임만 구분하는 검증 전 초기값이며 발끝 회전 각도 자체와 구분한다",
                    "knee_ang_$key",setOf("C"),gates=true))
                add(extra("${label}쪽 골반 내려감","goodmorning_hip_height_$key","골반",RepPhase.BOTTOM,-.50f,null,
                    "몸통을 숙이며 골반이 아래로 많이 내려갔어요",null,"주저앉지 말고 엉덩이를 뒤로 보내며 숙여 주세요",
                    "사용자 사진의 스쿼트형 하강 구분. 동측 발목 기준 골반 높이/정강이 길이를 반복 직전과 비교한다. 정강이 길이의 0.50 이상 내려가고 같은 쪽 무릎이 135° 미만인 경우에만 횟수에서 제외한다. 하강량 단독으로 제외하지 않는다",
                    "hip_ang_$key",setOf(if(key=="L")"SIDE_D" else "SIDE_B",if(key=="L")"E" else "A"),RepFormRef.REP_DELTA,gates=true)
                    .copy(requireObservedView=false))
            }
        }
        return listOf(oblique) + side + photoChecks + shoulder
    }
}

package com.example.trex_kotlin.posture

/** 사진에서 확인한 동작 설명을 반복 검사로 연결한다. 수치 띠는 사진에서 측정한 정답이 아닌 검증 전 초기값이다. */
object RaisePhotoSpecs {
    fun checks(ex: String): List<RepFormCheck> {
        val front=ex=="프런트 레이즈"
        val arms=setOf("B","C","D","SIDE_B","SIDE_D","A","E")
        val shoulderViews=arms
        fun check(name:String, feature:String, lo:Float?, hi:Float?, low:String?, high:String?, fix:String,
                  unit:String="°", ref:RepFormRef=RepFormRef.NONE, views:Set<String> = arms,
                  highlight:String="shoulder_arm_ang", reason:String, gateHi:Float?=null, gates:Boolean=true) = RepFormCheck(
            id="repform|$ex|$name", exercise=ex, condition="$name(반복)", bodyPart="어깨·팔",
            status=RuleStatus.BETA, feature=feature, phase=RepPhase.BOTTOM, stat=RepFormStat.MEDIAN, ref=ref,
            lo=lo, hi=hi, lowText=low, highText=high, lowLabel=if(lo==null)null else "낮음",
            highLabel=if(hi==null)null else "높음", fix=fix, reason=reason, unit=unit,
            cautions=listOf("사진은 동작 설명의 근거이며 임계값·촬영 정확도는 실기기 검증 전이다",
                "근육 긴장·견갑골의 회전·손목 회전은 직접 평가하지 않는다"),
            views=views, gates=gates, gateHi=gateHi, cue=name, highlight=highlight,
            minSamples=2, minReferenceSamples=2, minCoverage=.75f, requireObservedView=true,
            maxObservationGapMs=750L, strictObservationWindow=true)
        return buildList {
            add(check("고개 정면 유지","raise_head_turn",null,55f,null,
                "고개가 몸이 향한 방향에서 많이 돌아갔어요","고개를 몸이 향한 정면으로 편하게 돌려 주세요",
                highlight="head_yaw",gateHi=70f,
                reason="몸 좌표계에서 귀 중점-코 방향의 좌우각 절댓값. 반복 중앙값 55° 초과 안내, 70° 초과만 횟수 제외. 카메라를 바라보라는 뜻이 아니며 눈동자 시선은 보지 못한다")
                .copy(bodyPart="고개",phase=RepPhase.CYCLE,minSamples=5))
            add(check("고개 들고 숙임","raise_head_pitch",-45f,40f,
                "고개를 많이 숙이고 있어요","고개를 많이 들고 있어요",
                "고개를 편하게 세우고 몸이 향한 정면을 봐 주세요",highlight="head_pitch",gates=false,
                reason="반복 중 고개 기울기 중앙값. 작은 턱 움직임은 허용하고 -45~40° 밖에서만 안내한다")
                .copy(bodyPart="고개",phase=RepPhase.CYCLE,minSamples=5))
            for ((side,label) in listOf("L" to "왼팔","R" to "오른팔")) {
                add(check("$label 올림 높이","upperarm_vert_$side",60f,125f,
                    "$label 위팔이 어깨보다 많이 높아졌어요","$label 위팔이 어깨 높이까지 올라오지 않았어요",
                    "팔을 어깨 높이 부근까지만 올려 주세요",highlight="shoulder_arm_ang_$side",
                    reason="사진의 어깨 높이에서 위팔과 중력 위쪽의 각도는 약 90°. 반복의 올린 구간에서 60~125° 여유 띠를 사용한다"))
                val feature=if(front)"raise_front_plane_$side" else "raise_lateral_plane_$side"
                add(check("$label 올림 방향",feature,if(front)-60f else -35f,if(front)60f else 80f,
                    if(front)"$label 위팔이 몸 안쪽으로 많이 모였어요" else "$label 위팔이 몸 뒤쪽으로 많이 넘어갔어요",
                    if(front)"$label 위팔이 옆쪽으로 많이 벌어졌어요" else "$label 위팔이 앞쪽으로 많이 모였어요",
                    if(front)"양팔을 몸 앞으로 나란히 올려 주세요" else "팔꿈치를 조금 굽힌 채 옆에서 약간 앞쪽 사선으로 올려 주세요",
                    views=setOf("B","C","D"), highlight="shoulder_arm_ang_$side",gates=false,
                    reason=if(front)"몸의 전방 대비 같은 쪽 위팔의 수평 방향. ±60° 여유 띠. 방향 차이는 안내만 하고 횟수에서 빼지 않는다"
                        else "몸 바깥쪽 대비 위팔의 앞쪽 수평 방향. -35~80°로 자연스러운 사선을 허용한다. 정확한 사선 각도는 요구하지 않으며 안내만 한다"))
                add(check("$label 어깨 올라간 상태","raise_shoulder_gap_$side",.12f,null,
                    "$label 쪽 어깨가 올라가 귀와 어깨 사이가 많이 좁아요",null,
                    "어깨 힘을 빼고 내린 상태에서 팔을 올려 주세요",unit="",views=shoulderViews,
                    highlight="shoulder_h_$side",
                    reason="동측 귀-어깨 간격/몸통 길이의 올린 구간 중앙값. 0.12 미만의 뚜렷한 압축을 절대 기준으로 확인해 처음부터 으쓱한 자세가 기준이 되는 문제를 막는다. 체형·기기 검증 전 초기값이며 머리 숙임·기울임·가림은 유보한다"))
                add(check("$label 어깨 들림","raise_shoulder_lift_$side",null,if(front).12f else .18f,null,
                    "$label 쪽 귀와 어깨 사이가 많이 좁아졌어요",
                    "고개를 세우고 어깨를 과하게 으쓱하지 않으며 팔을 올려 주세요",unit="",
                    ref=RepFormRef.REP_DELTA,highlight="shoulder_h_$side",gateHi=if(front).18f else .28f,views=shoulderViews,
                    reason=if(front) "동측 귀-어깨 간격/몸통 길이 음수의 BOTTOM-TOP 차이. 프런트는 .12 안내/.18 횟수 제외로 올리는 중 뚜렷한 으쓱임을 확인한다. 작은 움직임은 허용하며 임계값은 실기기 검증 전이다"
                        else "같은 쪽 귀-어깨 간격을 몸통 길이로 정규화한 음수의 BOTTOM-TOP 차이. 머리 기울기와 가시성 확인 후 .18 안내/.28 횟수 제외. 정상적인 어깨의 작은 움직임은 허용한다"))
                if(front) add(check("$label 어깨 앞뒤 이동","raise_shoulder_fwd_$side",-.30f,.30f,
                    "$label 쪽 어깨와 몸통이 뒤로 많이 이동했어요","$label 쪽 어깨와 몸통이 앞으로 많이 이동했어요",
                    "어깨와 몸통이 앞뒤로 크게 흔들리지 않게 팔을 올려 주세요",unit="",
                    ref=RepFormRef.REP_DELTA, views=setOf("B","D"),highlight="torso_pitch",gates=false,
                    reason="같은 쪽 골반 대비 어깨의 전방 투영 / 몸통 길이의 BOTTOM-TOP 차이. ±.30 여유 띠로 안내만 한다. 견갑골 움직임만 분리해서 재는 값은 아니다"))
            }
        }
    }
}

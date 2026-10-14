package com.example.trex_kotlin.posture

/** 레이즈의 반복 판별 및 처음부터 유지된 큰 자세 이탈. 기존 변화량 검사와 함께 사용한다. */
object RaiseFormExtras {
    fun checks(ex:String):List<RepFormCheck> {
        fun c(name:String,feature:String,part:String,phase:RepPhase,stat:RepFormStat,lo:Float?,hi:Float?,low:String?,high:String?,fix:String,highlight:String,
              views:Set<String> = setOf("B","C","D","SIDE_B","SIDE_D","A","E"), gates:Boolean=true) = RepFormCheck(
            "repform|$ex|$name",ex,"$name(반복)",part,RuleStatus.BETA,feature,phase,stat,RepFormRef.NONE,lo,hi,low,high,
            if(lo==null)null else "부족",if(hi==null)null else "벗어남",fix,
            "팔별 반복 창과 관측 품질 검사를 사용한다. 운동 각도 기준은 검증 전의 보수적 초기값이며 실기기에서 추가 확인해야 한다",
            unit="°",cautions=listOf("검증 중인 교정 기준. 코치 모드에서 관측된 위반 반복만 제외하고 기록 모드는 유지한다"),
            views=views,gates=gates,cue=name,highlight=highlight,minSamples=if(phase==RepPhase.CYCLE)5 else 2,
            minCoverage=.75f,requireObservedView=true,maxObservationGapMs=750L,strictObservationWindow=true)
        return RaisePhotoSpecs.checks(ex) + buildList {
            for((side,label) in listOf("L" to "왼팔","R" to "오른팔")) {
                add(c("$label 올림 범위","upperarm_vert_$side","팔",RepPhase.CYCLE,RepFormStat.ROBUST_RANGE,20f,null,
                    "$label 위팔이 충분히 올라가지 않았어요",null,
                    "팔꿈치만 접지 말고 위팔을 함께 올려 주세요","shoulder_arm_ang_$side"))
                add(c("$label 고정 굽힘","elbow_$side","팔꿈치",RepPhase.BOTTOM,RepFormStat.MEDIAN,95f,null,
                    "$label 팔꿈치가 많이 접혀 있어요",null,
                    "편안하게 굽힌 팔꿈치 각도를 유지하며 올려 주세요","elbow_ang_$side",gates=false))
            }
            add(c("몸통 기울기","torso_pitch","상체",RepPhase.BOTTOM,RepFormStat.MEDIAN,-35f,45f,
                "몸통이 뒤로 많이 기울어 있어요","몸통이 앞으로 많이 기울어 있어요",
                "몸통을 세운 상태로 팔을 올려 주세요","torso_pitch",setOf("B","D")))
        }
    }
}

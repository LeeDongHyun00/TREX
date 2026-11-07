// Android 정본 동기화 — tools/sync_ios_core.py
package com.example.trex_kotlin.trainingload

import kotlin.math.exp
import kotlin.math.pow

/** 생리 측정값이 아닌 기록 기반 부하 지수. 계수의 근거/한계는 docs/MUSCLE_LOAD_IMPLEMENTATION.md. */
enum class Muscle(val label: String) {
    QUADS("앞 허벅지"), GLUTES("대둔근"), ABDUCTORS("옆 엉덩이"), ADDUCTORS("안쪽 허벅지"),
    HAMSTRINGS("뒤 허벅지"), CALVES("종아리"), HIP_FLEXORS("고관절 굽힘근"), ABS("복직근"),
    OBLIQUES("옆구리"), ERECTORS("척추기립근"), PECS("가슴"), DELTOIDS("어깨"), LATS("광배근"),
    TRAPS("승모근"), SERRATUS("전거근"), BICEPS("팔 앞쪽"), TRICEPS("팔 뒤쪽"), FOREARMS("전완"),
    // §130(2026-11-07) 46종 확장에서 추가 — 2D 근육도(atlas-2d.js)에는 3D 모델에 없는 근육이라 손 그린 근사 영역(export_atlas_2d.py MANUAL_REGIONS)을 덮는 근육 위에 겹쳐 둔다.
    // 이름으로 저장되므로(MuscleLoadStore) 끝에만 붙인다. 복횡근·다열근은 표면 EMG 로 분리 측정된 적이 없어 넣지 않는다.
    ROTATOR_CUFF("회전근개"), RHOMBOIDS("능형근"), TIBIALIS("정강이 앞"), QUADRATUS_LUMBORUM("요방형근")
}
enum class LoadUnit { REP, PAIR, EACH, SECONDS }
/** 온보딩 '보유 기구' 비트 → 이 값의 대응은 MuscleLoadAdapter.loadEquipment. 끝에만 붙인다(iOS 는 이름으로 넘긴다, IosDomain). */
enum class Equipment { NONE, DUMBBELL, BARBELL, LAT_MACHINE, DIP_BAR, PULLUP_BAR, BENCH,
    BAND, CABLE, LEG_PRESS, LEG_CURL_MACHINE, CHEST_PRESS_MACHINE, ROW_MACHINE, STABILITY_BALL, RACK }

data class ExerciseLoadProfile(
    val id: String, val name: String, val weights: Map<Muscle, Double>,
    val sources: List<String>, val evidence: String,
    val unit: LoadUnit = LoadUnit.REP, val bodyweight: Boolean = false,
    val equipment: Equipment = Equipment.NONE,
) {
    val primary: List<Muscle> get() = weights.filterValues { it >= .75 }.keys.toList()
}

object MuscleLoadCatalog {
    const val VERSION = 1
    private fun p(id: String, name: String, sources: String, evidence: String,
        unit: LoadUnit = LoadUnit.REP, body: Boolean = false, equipment: Equipment = Equipment.NONE,
        vararg muscles: Pair<Muscle, Double>) = ExerciseLoadProfile(id, name, muscles.toMap(),
        sources.split(",").map { if (it.startsWith("http")) it else "https://pubmed.ncbi.nlm.nih.gov/$it/" }, evidence, unit, body, equipment)
    // 역할 가중치는 EMG %를 옮긴 값이 아니다. 주동/보조/안정 역할에 부여한 제품 초기값이다.
    val all = listOf(
        p("squat", "기본 스쿼트", "19075302", "바벨 스쿼트 연구를 맨몸 동작에 적용", body=true,
            muscles=arrayOf(Muscle.QUADS to 1.0, Muscle.GLUTES to .8, Muscle.ADDUCTORS to .3, Muscle.HAMSTRINGS to .2, Muscle.CALVES to .2, Muscle.ERECTORS to .15, Muscle.ABS to .1)),
        p("lunge", "런지", "32236133,39982282", "전방 런지 연구 · 부하/보폭 차이", LoadUnit.PAIR, true,
            muscles=arrayOf(Muscle.QUADS to 1.0, Muscle.GLUTES to .8, Muscle.ABDUCTORS to .4, Muscle.HAMSTRINGS to .3, Muscle.ADDUCTORS to .25, Muscle.CALVES to .2, Muscle.OBLIQUES to .1)),
        p("barbell_lunge", "바벨 런지", "39982282", "외부 중량 전방 런지 연구 · 중량 미입력", LoadUnit.PAIR, equipment=Equipment.BARBELL,
            muscles=arrayOf(Muscle.QUADS to 1.0, Muscle.GLUTES to .8, Muscle.ABDUCTORS to .4, Muscle.HAMSTRINGS to .3, Muscle.ADDUCTORS to .25, Muscle.CALVES to .2, Muscle.ERECTORS to .15)),
        p("side_lunge", "사이드 런지", "23584319,19321909", "측방 런지 관절역학 + 전방 런지 근활성에서 추론", LoadUnit.PAIR, true,
            muscles=arrayOf(Muscle.QUADS to .9, Muscle.GLUTES to .75, Muscle.ADDUCTORS to .6, Muscle.ABDUCTORS to .35, Muscle.HAMSTRINGS to .25, Muscle.OBLIQUES to .1)),
        p("cross_lunge", "크로스 런지", "32236133,19321909", "유사 런지 연구와 관절 작용에 근거한 추론", LoadUnit.PAIR, true,
            muscles=arrayOf(Muscle.QUADS to .9, Muscle.GLUTES to .8, Muscle.ABDUCTORS to .45, Muscle.ADDUCTORS to .3, Muscle.HAMSTRINGS to .25, Muscle.OBLIQUES to .1)),
        p("deadlift", "바벨 데드리프트", "28151780", "바벨 데드리프트 직접 연구 · 중량 미입력", equipment=Equipment.BARBELL,
            muscles=arrayOf(Muscle.GLUTES to 1.0, Muscle.HAMSTRINGS to .8, Muscle.ERECTORS to .55, Muscle.QUADS to .4, Muscle.ADDUCTORS to .25, Muscle.FOREARMS to .25, Muscle.LATS to .15, Muscle.TRAPS to .15)),
        p("good_morning", "굿모닝", "25653899", "바벨 굿모닝 연구 · 중량/변형 차이", equipment=Equipment.BARBELL,
            muscles=arrayOf(Muscle.HAMSTRINGS to 1.0, Muscle.GLUTES to .75, Muscle.ERECTORS to .5, Muscle.ABS to .1)),
        p("hip_thrust", "힙 쓰러스트", "28151780", "바벨 힙 쓰러스트 연구 · 중량/벤치 조건 차이", equipment=Equipment.BENCH,
            muscles=arrayOf(Muscle.GLUTES to 1.0, Muscle.HAMSTRINGS to .35, Muscle.QUADS to .2, Muscle.ABS to .15, Muscle.ERECTORS to .15)),
        p("overhead_press", "오버헤드 프레스", "35936912", "프레스 직접 연구 · 덤벨/바벨 및 방향 차이", equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.DELTOIDS to 1.0, Muscle.TRICEPS to .6, Muscle.TRAPS to .3, Muscle.SERRATUS to .2, Muscle.PECS to .15, Muscle.ABS to .1)),
        p("lat_pulldown", "랫풀 다운", "40981044", "랫풀 다운 직접 연구 · 그립 차이", equipment=Equipment.LAT_MACHINE,
            muscles=arrayOf(Muscle.LATS to 1.0, Muscle.BICEPS to .5, Muscle.TRAPS to .3, Muscle.DELTOIDS to .2, Muscle.FOREARMS to .2)),
        p("dips", "딥스", "36293792", "바 딥스 직접 연구 · 벤치 딥스와 구분", body=true, equipment=Equipment.DIP_BAR,
            muscles=arrayOf(Muscle.TRICEPS to 1.0, Muscle.PECS to .8, Muscle.DELTOIDS to .3, Muscle.SERRATUS to .2, Muscle.ABS to .1)),
        p("dumbbell_curl", "덤벨 컬", "30013836,17545433", "컬 근활성 + 상완근 해부 연구", equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.BICEPS to 1.0, Muscle.FOREARMS to .4)),
        p("barbell_curl", "바벨 컬", "30013836,17545433", "바벨/EZ바 컬 직접 연구", equipment=Equipment.BARBELL,
            muscles=arrayOf(Muscle.BICEPS to 1.0, Muscle.FOREARMS to .4)),
        p("lateral_raise", "사이드 레터럴 레이즈", "32824894", "측면 들기 직접 연구 · 어깨는 한 근육군으로 표시", equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.DELTOIDS to 1.0, Muscle.TRAPS to .35, Muscle.SERRATUS to .2)),
        p("front_raise", "프런트 레이즈", "32824894", "전면 들기 직접 연구 · 어깨는 한 근육군으로 표시", equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.DELTOIDS to 1.0, Muscle.PECS to .3, Muscle.TRAPS to .2, Muscle.SERRATUS to .2)),
        p("upright_row", "업라이트로우", "22362088", "업라이트로우 직접 연구 · 그립 폭 차이", equipment=Equipment.BARBELL,
            muscles=arrayOf(Muscle.DELTOIDS to 1.0, Muscle.TRAPS to .8, Muscle.BICEPS to .3, Muscle.FOREARMS to .2)),
        p("pushup", "푸쉬업", "20664364,24511343", "일반 푸쉬업 직접 연구", body=true,
            muscles=arrayOf(Muscle.PECS to 1.0, Muscle.TRICEPS to .75, Muscle.SERRATUS to .45, Muscle.DELTOIDS to .35, Muscle.ABS to .15)),
        p("knee_pushup", "니 푸쉬업", "20664364,27693098", "일반/무릎 플러스 변형 연구 · 일반 니 푸쉬업에 추론", body=true,
            muscles=arrayOf(Muscle.PECS to .8, Muscle.TRICEPS to .6, Muscle.SERRATUS to .35, Muscle.DELTOIDS to .25, Muscle.ABS to .1)),
        p("y_raise", "Y 레이즈", "23068897", "견갑면 팔 올리기 연구 · 자세/지지 조건에 따른 추론", body=true,   // 기구 없음(엎드려 맨몸) — 감사 F1 불일치 수정
            muscles=arrayOf(Muscle.TRAPS to 1.0, Muscle.DELTOIDS to .4, Muscle.SERRATUS to .35)),
        p("plank", "플랭크", "30856100", "플랭크 직접 연구 · 버틴 시간 기준", LoadUnit.SECONDS, true,
            muscles=arrayOf(Muscle.ABS to 1.0, Muscle.OBLIQUES to .5, Muscle.SERRATUS to .25, Muscle.DELTOIDS to .2, Muscle.GLUTES to .15, Muscle.ERECTORS to .1)),
        p("side_crunch", "스탠딩 사이드 크런치", "12937449", "체간 측굴 연구를 서서 수행하는 동작에 적용", LoadUnit.EACH, true,
            muscles=arrayOf(Muscle.OBLIQUES to 1.0, Muscle.ABS to .35, Muscle.ERECTORS to .15)),
        p("knee_up", "스탠딩 니업", "9118976,34455371", "다리 들기/고관절 굴곡 연구에서 추론", LoadUnit.EACH, true,
            muscles=arrayOf(Muscle.HIP_FLEXORS to 1.0, Muscle.ABS to .35, Muscle.OBLIQUES to .2, Muscle.QUADS to .25)),
        p("hanging_leg_raise", "행잉 레그 레이즈", "9118976,27065536", "누운 다리 들기 연구 + 매달리기 관절 작용에서 추론", body=true, equipment=Equipment.PULLUP_BAR,   // 맨몸(철봉에 매달림) — 감사 F1 불일치 수정
            muscles=arrayOf(Muscle.HIP_FLEXORS to 1.0, Muscle.ABS to .8, Muscle.OBLIQUES to .3, Muscle.FOREARMS to .3, Muscle.LATS to .15)),
        p("crunch", "크런치", "12937449", "컬업/체간 굴곡 직접 연구", body=true,
            muscles=arrayOf(Muscle.ABS to 1.0, Muscle.OBLIQUES to .3)),
        p("leg_raise", "레그 레이즈", "27065536,34455371", "누운 다리 들기 직접 연구", body=true,
            muscles=arrayOf(Muscle.HIP_FLEXORS to 1.0, Muscle.ABS to .6, Muscle.OBLIQUES to .25, Muscle.QUADS to .3)),
        p("scissor_cross", "시저 크로스", "9118976,20625774", "다리 들기 연구 + 교차 내전 동작의 해부학적 추론", LoadUnit.EACH, true,
            muscles=arrayOf(Muscle.HIP_FLEXORS to .8, Muscle.ABS to .6, Muscle.ADDUCTORS to .5, Muscle.OBLIQUES to .25, Muscle.QUADS to .2)),
        // ── §130 AIHub 밖 46종(2026-11-07) — 근거는 docs/EXERCISE_EXPANSION_46.md. 카메라 자세 평가 없음(프로필 없음 → postureSupported 거짓).
        // 가중치 원칙은 위와 같다(역할 초기값, EMG % 아님). 인용 논문이 문서의 역할 분류와 반대인 세 종목(의자 스쿼트·리버스 런지·싱글레그 브릿지)은 논문 결과를 따랐다.
        // 밴드·머신·케이블 종목은 장력/표시 중량을 모른다(B=1) — 중량·RIR 입력이 생기기 전까지 같은 횟수는 같은 부하다.
        // 하체
        p("chair_squat", "의자 스쿼트", "33728287", "앉았다 일어나기 EMG(Kim·Ju 2021) — 전경골근이 대퇴직근보다 높았다(모든 의자 높이); 대둔근은 미측정, 관절 작용 추론", body=true,
            muscles=arrayOf(Muscle.QUADS to 1.0, Muscle.TIBIALIS to .8, Muscle.GLUTES to .6, Muscle.ERECTORS to .3, Muscle.HAMSTRINGS to .2, Muscle.ABDUCTORS to .1, Muscle.ABS to .1)),
        p("goblet_squat", "고블릿 스쿼트", "34341315", "고블릿 스쿼트 직접 EMG(Collins 2021, 체중 30% 부하) — 광근·햄스트링 측정; 대둔근·내전근·몸통은 추론 · 중량 미입력", equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.QUADS to 1.0, Muscle.GLUTES to .8, Muscle.ADDUCTORS to .35, Muscle.HAMSTRINGS to .3, Muscle.ERECTORS to .2, Muscle.ABS to .15, Muscle.OBLIQUES to .1)),
        p("reverse_lunge", "리버스 런지", "https://www.mdpi.com/2076-3417/14/24/11480", "맨몸 리버스 런지 EMG(2024, 20명) — 강화 수준은 앞다리 대퇴직근·지지다리 중둔근뿐, 대둔근은 미달; 광근·내전근·몸통은 추론", LoadUnit.PAIR, true,
            muscles=arrayOf(Muscle.QUADS to 1.0, Muscle.ABDUCTORS to .8, Muscle.GLUTES to .6, Muscle.HAMSTRINGS to .3, Muscle.ADDUCTORS to .2, Muscle.ERECTORS to .1, Muscle.OBLIQUES to .1)),
        p("step_up", "스텝업", "32236133", "측방 스텝업 EMG(Muyor 2020, 5RM 60%)를 정면 스텝업에 적용 — 광근·중둔근·대둔근 순", LoadUnit.PAIR, true,
            muscles=arrayOf(Muscle.QUADS to 1.0, Muscle.GLUTES to .8, Muscle.ABDUCTORS to .5, Muscle.HAMSTRINGS to .3, Muscle.CALVES to .25, Muscle.OBLIQUES to .1, Muscle.ERECTORS to .1)),
        p("dumbbell_rdl", "덤벨 루마니안 데드리프트", "35162922", "바벨 RDL EMG(Coratella 2022, 80% 1RM)를 덤벨에 적용 — 대둔근·중둔근·햄스트링·기립근 직접 · 중량 미입력", equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.HAMSTRINGS to 1.0, Muscle.GLUTES to .8, Muscle.ERECTORS to .5, Muscle.ABDUCTORS to .2, Muscle.ADDUCTORS to .2, Muscle.FOREARMS to .2, Muscle.ABS to .1)),
        p("glute_bridge", "글루트 브릿지", "29706723,23160432", "브리지 EMG(Khaiyat 2018 여성 12명; Selkowitz 2013 세침) — 대둔근·대퇴이두근 직접, 발 위치·무릎 각도에 따라 변함", body=true,
            muscles=arrayOf(Muscle.GLUTES to 1.0, Muscle.HAMSTRINGS to .4, Muscle.ABDUCTORS to .3, Muscle.ERECTORS to .15, Muscle.ABS to .1)),
        p("leg_press", "레그 프레스", "32605065", "레그 프레스 변형 체계적 고찰(Martín-Fuentes 2020) — 광근·대퇴직근·대퇴이두근·비복근 종합; 둔근·내전근은 추론 · 머신 표시 중량 미입력", equipment=Equipment.LEG_PRESS,
            muscles=arrayOf(Muscle.QUADS to 1.0, Muscle.GLUTES to .75, Muscle.ADDUCTORS to .3, Muscle.HAMSTRINGS to .25, Muscle.CALVES to .2, Muscle.ABS to .1)),
        p("seated_leg_curl", "시티드 레그 컬", "33009197", "시티드 레그 컬 훈련 MRI(Maeo 2021) — 햄스트링 개별 체적; 비복근은 추론 · 머신 표시 중량 미입력", equipment=Equipment.LEG_CURL_MACHINE,
            muscles=arrayOf(Muscle.HAMSTRINGS to 1.0, Muscle.CALVES to .3)),
        p("calf_raise", "카프 레이즈", "38156065", "스탠딩 카프 레이즈 훈련 MRI(Kinoshita·Maeo 2023, 공동 제1저자) — 비복근 내외측두·가자미근 체적; 균형 근육은 추론", body=true,
            muscles=arrayOf(Muscle.CALVES to 1.0, Muscle.TIBIALIS to .1, Muscle.ABDUCTORS to .1)),
        p("band_walk", "사이드 밴드 워크", "30615490,23160432", "밴드 사이드 스테핑 EMG(Lewis 2018, 22명) + 둔근 세침(Selkowitz 2013) — 대둔근·중둔근·대퇴근막장근 직접 · 밴드 장력 미입력", LoadUnit.PAIR, equipment=Equipment.BAND,
            muscles=arrayOf(Muscle.ABDUCTORS to 1.0, Muscle.GLUTES to .6, Muscle.QUADS to .15, Muscle.OBLIQUES to .1, Muscle.ERECTORS to .1)),
        p("quadruped_hip_extension", "네발기기 힙 익스텐션", "23160432", "네발기기 고관절 폄 세침 EMG(Selkowitz 2013) — 중둔근·상부 대둔근·대퇴근막장근; 햄스트링·몸통은 추론", LoadUnit.PAIR, true,
            muscles=arrayOf(Muscle.GLUTES to 1.0, Muscle.ABDUCTORS to .5, Muscle.HAMSTRINGS to .4, Muscle.ERECTORS to .2, Muscle.ABS to .15)),
        p("bulgarian_split_squat", "불가리안 스플릿 스쿼트", "40867012", "불가리안 스플릿 스쿼트 EMG(2025, 훈련 여성 21명) — 둔근·광근·대퇴직근·햄스트링·기립근 직접", LoadUnit.PAIR, true,
            muscles=arrayOf(Muscle.QUADS to 1.0, Muscle.GLUTES to .8, Muscle.ABDUCTORS to .4, Muscle.HAMSTRINGS to .3, Muscle.ADDUCTORS to .25, Muscle.ERECTORS to .2, Muscle.ABS to .1)),
        p("single_leg_bridge", "싱글레그 글루트 브릿지", "28900560", "싱글레그 브리지 EMG(Lehecka 2017, 28명) — 무릎 90° 에서 햄스트링 75% > 중둔근 58%·대둔근 51% MVIC; 135° 변형에서만 햄스트링이 낮다", LoadUnit.PAIR, true,
            muscles=arrayOf(Muscle.HAMSTRINGS to 1.0, Muscle.GLUTES to .8, Muscle.ABDUCTORS to .75, Muscle.ERECTORS to .15, Muscle.OBLIQUES to .1)),
        p("band_squat", "밴드 스쿼트", "34341315", "밴드 동일 조건 EMG 미확보 — 고블릿·바벨 스쿼트 EMG(Collins 2021)에서 추론 · 밴드 장력 미입력", equipment=Equipment.BAND,
            muscles=arrayOf(Muscle.QUADS to 1.0, Muscle.GLUTES to .8, Muscle.ADDUCTORS to .3, Muscle.HAMSTRINGS to .25, Muscle.ERECTORS to .2, Muscle.ABS to .1)),
        p("band_rdl", "밴드 루마니안 데드리프트", "35162922", "밴드 동일 조건 EMG 미확보 — 바벨 RDL EMG(Coratella 2022)에서 추론 · 밴드 장력 미입력", equipment=Equipment.BAND,
            muscles=arrayOf(Muscle.HAMSTRINGS to 1.0, Muscle.GLUTES to .8, Muscle.ERECTORS to .5, Muscle.ADDUCTORS to .2, Muscle.FOREARMS to .2, Muscle.ABS to .1)),
        // 상체
        p("wall_pushup", "벽 푸쉬업", "14977678,35599715", "벽 푸시업 플러스 EMG(Ludewig 2004 — 전거근·상부 승모근만) + 푸시업 변형 고찰(Kowalski 2022); 대흉근·삼두근은 푸시업 문헌 적용, 가장 낮은 부하", body=true,
            muscles=arrayOf(Muscle.PECS to .75, Muscle.TRICEPS to .55, Muscle.SERRATUS to .3, Muscle.DELTOIDS to .25, Muscle.TRAPS to .15, Muscle.ROTATOR_CUFF to .1, Muscle.ABS to .1)),
        p("incline_pushup", "인클라인 푸쉬업", "35599715", "인클라인 푸시업 포함 변형 고찰(Kowalski 2022) — 대흉근·삼두근·전거근 종합; 지지대가 높을수록 부하 감소", body=true,
            muscles=arrayOf(Muscle.PECS to .8, Muscle.TRICEPS to .6, Muscle.SERRATUS to .4, Muscle.DELTOIDS to .3, Muscle.ROTATOR_CUFF to .1, Muscle.ABS to .1, Muscle.GLUTES to .1)),
        p("dumbbell_bench_press", "덤벨 벤치 프레스", "21225489", "덤벨 벤치 프레스 직접 EMG(Saeterbakken 2011) — 대흉근·전면 삼각근·이두근·삼두근 · 중량 미입력", equipment=Equipment.BENCH,
            muscles=arrayOf(Muscle.PECS to 1.0, Muscle.TRICEPS to .6, Muscle.DELTOIDS to .5, Muscle.BICEPS to .15, Muscle.ROTATOR_CUFF to .15)),
        p("one_arm_dumbbell_row", "원암 덤벨 로우", "19620925,15228624", "원암 케이블 로우(Fenwick 2009)·시티드 로우(Lehman 2004) EMG에서 추론 — 광배근·이두근·중부 승모근/능형근 · 중량 미입력", LoadUnit.PAIR, equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.LATS to 1.0, Muscle.TRAPS to .6, Muscle.RHOMBOIDS to .6, Muscle.BICEPS to .5, Muscle.DELTOIDS to .4, Muscle.FOREARMS to .25, Muscle.ERECTORS to .2, Muscle.OBLIQUES to .15, Muscle.ROTATOR_CUFF to .1)),
        p("band_row", "밴드 로우", "15228624,19620925", "밴드 동일 조건 EMG 미확보 — 시티드/원암 로우 EMG에서 추론 · 밴드 장력 미입력", equipment=Equipment.BAND,
            muscles=arrayOf(Muscle.LATS to 1.0, Muscle.TRAPS to .6, Muscle.RHOMBOIDS to .6, Muscle.BICEPS to .5, Muscle.DELTOIDS to .35, Muscle.FOREARMS to .2, Muscle.ERECTORS to .15, Muscle.ROTATOR_CUFF to .1)),
        p("seated_cable_row", "시티드 케이블 로우", "15228624", "시티드 로우 직접 EMG(Lehman 2004) — 광배근·이두근·중부 승모근/능형근(표면 EMG 한 그룹) · 머신 표시 중량 미입력", equipment=Equipment.ROW_MACHINE,
            muscles=arrayOf(Muscle.LATS to 1.0, Muscle.TRAPS to .6, Muscle.RHOMBOIDS to .6, Muscle.BICEPS to .5, Muscle.DELTOIDS to .35, Muscle.FOREARMS to .2, Muscle.ERECTORS to .15, Muscle.ROTATOR_CUFF to .1)),
        p("triceps_pushdown", "케이블 트라이셉스 푸시다운", "32153422", "삼두 푸시다운 직접 EMG(Hussain 2020, 25명) — 장두·외측두·내측두 · 머신 표시 중량 미입력", equipment=Equipment.CABLE,
            muscles=arrayOf(Muscle.TRICEPS to 1.0, Muscle.FOREARMS to .2, Muscle.DELTOIDS to .1, Muscle.ABS to .1)),
        p("band_chest_press", "밴드 체스트 프레스", "18076235", "밴드 동일 조건 EMG 미확보 — 서서 케이블 프레스(Santana 2007)·푸시업 문헌에서 추론 · 밴드 장력 미입력", equipment=Equipment.BAND,
            muscles=arrayOf(Muscle.PECS to 1.0, Muscle.TRICEPS to .6, Muscle.DELTOIDS to .4, Muscle.SERRATUS to .3, Muscle.ABS to .15, Muscle.OBLIQUES to .15, Muscle.ROTATOR_CUFF to .1)),
        p("band_shoulder_press", "밴드 숄더 프레스", "23096062", "밴드 동일 조건 EMG 미확보 — 덤벨/바벨 숄더 프레스 EMG(Saeterbakken 2013)에서 추론 · 밴드 장력 미입력", equipment=Equipment.BAND,
            muscles=arrayOf(Muscle.DELTOIDS to 1.0, Muscle.TRICEPS to .6, Muscle.TRAPS to .3, Muscle.SERRATUS to .2, Muscle.ABS to .1, Muscle.ROTATOR_CUFF to .1)),
        p("dumbbell_floor_press", "덤벨 플로어 프레스", "21225489", "덤벨 벤치 EMG(Saeterbakken 2011)를 바닥이 제한하는 가동 범위에 적용 · 중량 미입력", equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.PECS to 1.0, Muscle.TRICEPS to .7, Muscle.DELTOIDS to .4, Muscle.ROTATOR_CUFF to .15)),
        p("pike_pushup", "파이크 푸쉬업", "https://www.nasm.org/resource-center/exercise-library/pike-push-up,35599715", "파이크 푸시업 독립 EMG 미확보 — NASM 동작 설명 + 푸시업 변형 고찰에서 추론", body=true,
            muscles=arrayOf(Muscle.DELTOIDS to 1.0, Muscle.TRICEPS to .75, Muscle.PECS to .4, Muscle.SERRATUS to .3, Muscle.TRAPS to .3, Muscle.ROTATOR_CUFF to .15, Muscle.ABS to .15)),
        p("iyt_raise", "I-Y-T 레이즈", "https://www.acefitness.org/continuing-education/certified/april-2018/6959/ace-sponsored-research-what-is-the-best-back-exercise/", "ACE 의뢰 등 운동 EMG(2018, I-Y-T 포함, 19명) — 중·하부 승모근·극하근 직접; 동료심사 논문과 구분", body=true,
            muscles=arrayOf(Muscle.TRAPS to 1.0, Muscle.RHOMBOIDS to .5, Muscle.DELTOIDS to .5, Muscle.ROTATOR_CUFF to .4, Muscle.ERECTORS to .2)),
        p("face_pull", "케이블 페이스 풀", "https://www.nasm.org/resource-center/exercise-library/face-pull", "로프 페이스 풀 독립 EMG 미확보 — NASM 동작 설명(후면 삼각근·능형근·중부 승모근·극하근) · 머신 표시 중량 미입력", equipment=Equipment.CABLE,
            muscles=arrayOf(Muscle.DELTOIDS to 1.0, Muscle.RHOMBOIDS to .75, Muscle.TRAPS to .6, Muscle.ROTATOR_CUFF to .5, Muscle.BICEPS to .2, Muscle.ABS to .1)),
        p("machine_chest_press", "머신 체스트 프레스", "https://www.nasm.org/resource-center/exercise-library/chest-press-machine,21225489", "시티드 머신 자체 실험 미확보 — NASM 설명 + 스미스/바벨/덤벨 프레스 EMG에서 추론 · 머신 표시 중량 미입력", equipment=Equipment.CHEST_PRESS_MACHINE,
            muscles=arrayOf(Muscle.PECS to 1.0, Muscle.TRICEPS to .6, Muscle.DELTOIDS to .4, Muscle.SERRATUS to .2, Muscle.ROTATOR_CUFF to .1)),
        p("band_reverse_fly", "밴드 리버스 플라이", "29599855", "밴드 리버스 플라이 직접 EMG(Bergquist 2018, 10RM) — 후면·중간 삼각근·상부 승모근; 능형근은 추론 · 밴드 장력 미입력", equipment=Equipment.BAND,
            muscles=arrayOf(Muscle.DELTOIDS to 1.0, Muscle.TRAPS to .6, Muscle.RHOMBOIDS to .5, Muscle.ROTATOR_CUFF to .2, Muscle.ABS to .1)),
        p("inverted_row", "인버티드 로우", "19620925,https://www.acefitness.org/continuing-education/certified/april-2018/6959/ace-sponsored-research-what-is-the-best-back-exercise/", "인버티드 로우 직접 EMG(Fenwick 2009 — 광배근·등 위쪽·고관절 폄근·몸통; ACE 2018) · 바 높이로 부하 변함", body=true, equipment=Equipment.RACK,
            muscles=arrayOf(Muscle.LATS to 1.0, Muscle.TRAPS to .75, Muscle.RHOMBOIDS to .6, Muscle.BICEPS to .5, Muscle.DELTOIDS to .4, Muscle.ERECTORS to .3, Muscle.FOREARMS to .25, Muscle.GLUTES to .2, Muscle.ABS to .15, Muscle.ROTATOR_CUFF to .1)),
        p("pullup", "풀업", "21068680", "오버핸드 풀업 직접 EMG(Youdas 2010, 25명) — 광배근·이두근·극하근·하부 승모근·대흉근·기립근·외복사근", body=true, equipment=Equipment.PULLUP_BAR,
            muscles=arrayOf(Muscle.LATS to 1.0, Muscle.BICEPS to .8, Muscle.TRAPS to .4, Muscle.ROTATOR_CUFF to .35, Muscle.FOREARMS to .35, Muscle.PECS to .2, Muscle.ERECTORS to .15, Muscle.OBLIQUES to .15)),
        // 복근
        p("reverse_crunch", "리버스 크런치", "16649890", "리버스 크런치 직접 EMG(Escamilla 2006, 21명) — 복직근 상하부·내외복사근·대퇴직근", body=true,
            muscles=arrayOf(Muscle.ABS to 1.0, Muscle.OBLIQUES to .4, Muscle.HIP_FLEXORS to .35)),
        p("bicycle_crunch", "바이시클 크런치", "https://www.acefitness.org/about-ace/press-room/in-the-news/246/american-council-on-exercise-ace-sponsored-study-reveals-best-and-worst-abdominal-exercises/", "ACE/SDSU 복근 13종 EMG(2001, 30명) — 복직근 1위·복사근 2위; 기관 보고, 동료심사 논문과 구분", LoadUnit.EACH, true,
            muscles=arrayOf(Muscle.ABS to 1.0, Muscle.OBLIQUES to .8, Muscle.HIP_FLEXORS to .3)),
        p("ball_crunch", "짐볼 크런치", "17530978", "짐볼 크런치 직접 EMG(Sternlicht 2007, 41명) — 복직근 상하부·외복사근; 공 위치에 따라 변함", body=true, equipment=Equipment.STABILITY_BALL,
            muscles=arrayOf(Muscle.ABS to 1.0, Muscle.OBLIQUES to .35)),
        p("captains_chair_knee_raise", "캡틴체어 니 레이즈", "https://www.acefitness.org/about-ace/press-room/in-the-news/246/american-council-on-exercise-ace-sponsored-study-reveals-best-and-worst-abdominal-exercises/,16649890", "ACE/SDSU 2001 캡틴체어 + 스트랩 니업 EMG(Escamilla 2006)에서 추론 — 고관절 굽힘근·복직근 · 기구는 딥스 바(캡틴체어 겸용) 보유로 가늠", body=true, equipment=Equipment.DIP_BAR,
            muscles=arrayOf(Muscle.HIP_FLEXORS to 1.0, Muscle.ABS to .8, Muscle.OBLIQUES to .35, Muscle.QUADS to .2, Muscle.LATS to .1, Muscle.FOREARMS to .1)),
        p("russian_twist", "러시안 트위스트", "https://www.nasm.org/resource-center/exercise-library/russian-twist", "발 지지·무중량 조건 직접 EMG 미확보 — NASM 동작 설명(외·내복사근·복직근)", LoadUnit.EACH, true,
            muscles=arrayOf(Muscle.OBLIQUES to 1.0, Muscle.ABS to .5, Muscle.HIP_FLEXORS to .25, Muscle.ERECTORS to .1)),
        // 코어 — 버티기·운반은 초 단위. 한쪽씩 버티는 종목(무릎 사이드 플랭크·사이드 플랭크·수트케이스 캐리·팔로프)도 좌우 같은 부하로 적는다(어느 쪽을 했는지 모름).
        // 데드버그·버드독은 팔다리를 번갈아 뻗는 교대 동작 — §130 규칙(교대 = EACH)대로 EACH. PAIR 로 두면 같은 '8회' 가 바이시클보다 쪽당 2배였다(근거 감사 2026-11-07 F3)
        p("dead_bug", "데드버그", "11689975", "데드버그 직접 EMG(Souza 2001, 12명) — 복직근·복사근; 복횡근·다열근은 미측정", LoadUnit.EACH, true,
            muscles=arrayOf(Muscle.ABS to 1.0, Muscle.OBLIQUES to .6, Muscle.HIP_FLEXORS to .3, Muscle.DELTOIDS to .15)),
        p("bird_dog", "버드독", "11689975,22436839", "버드독 직접 EMG(Souza 2001; García-Vaquero 2012) — 기립근·대둔근·복직근·복사근", LoadUnit.EACH, true,
            muscles=arrayOf(Muscle.ERECTORS to 1.0, Muscle.GLUTES to .8, Muscle.OBLIQUES to .4, Muscle.ABS to .3, Muscle.ABDUCTORS to .2, Muscle.DELTOIDS to .15, Muscle.HAMSTRINGS to .15)),
        p("knee_plank", "무릎 플랭크", "22436839,https://www.mayoclinic.org/healthy-lifestyle/fitness/in-depth/core-strength/art-20546851", "무릎 지지 직접 EMG 미확보 — 앞 브리지 EMG(García-Vaquero 2012)에서 추론, 일반 플랭크보다 낮은 부하", LoadUnit.SECONDS, true,
            muscles=arrayOf(Muscle.ABS to .8, Muscle.OBLIQUES to .4, Muscle.SERRATUS to .2, Muscle.DELTOIDS to .15, Muscle.ERECTORS to .1, Muscle.GLUTES to .1)),
        p("knee_side_plank", "무릎 사이드 플랭크", "22436839,11415616,https://www.mayoclinic.org/healthy-lifestyle/fitness/in-depth/core-strength/art-20546851", "무릎 지지 직접 EMG 미확보 — 사이드 브리지 EMG(García-Vaquero 2012)·요방형근 세침(McGill 1996)에서 추론", LoadUnit.SECONDS, true,
            muscles=arrayOf(Muscle.OBLIQUES to .8, Muscle.QUADRATUS_LUMBORUM to .6, Muscle.ABDUCTORS to .3, Muscle.ABS to .3, Muscle.SERRATUS to .2, Muscle.DELTOIDS to .15, Muscle.ERECTORS to .1)),
        p("side_plank", "사이드 플랭크", "22436839,11415616", "사이드 브리지 직접 EMG(García-Vaquero 2012 — 복직근·내외복사근·기립근) + 요방형근 세침(McGill 1996, 4명)", LoadUnit.SECONDS, true,
            muscles=arrayOf(Muscle.OBLIQUES to 1.0, Muscle.QUADRATUS_LUMBORUM to .75, Muscle.ABDUCTORS to .4, Muscle.ABS to .35, Muscle.SERRATUS to .25, Muscle.DELTOIDS to .2, Muscle.ERECTORS to .15)),
        p("pallof_press", "팔로프 프레스", "https://doi.org/10.1519/SSC.0000000000000596", "팔로프 프레스 기술 해설(Mullane·Turner·Bishop 2021 SCJ) — 새 EMG 실험 아님; 회전 저항 역할을 정성 적용 · 밴드 장력 미입력", LoadUnit.PAIR, equipment=Equipment.BAND,
            muscles=arrayOf(Muscle.OBLIQUES to 1.0, Muscle.ABS to .6, Muscle.ERECTORS to .2, Muscle.SERRATUS to .2, Muscle.DELTOIDS to .2, Muscle.ABDUCTORS to .15)),
        p("farmer_carry", "파머스 캐리", "38665162", "로디드 캐리 직접 EMG(Ellestad 2024) — 복직근·외복사근·최장근·다열근 표면 EMG; 그립·승모근·보행은 추론 · 중량 미입력", LoadUnit.SECONDS, equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.FOREARMS to 1.0, Muscle.ERECTORS to .8, Muscle.ABS to .5, Muscle.OBLIQUES to .5, Muscle.TRAPS to .5, Muscle.QUADRATUS_LUMBORUM to .3, Muscle.GLUTES to .2, Muscle.QUADS to .15, Muscle.CALVES to .15)),
        p("suitcase_carry", "수트케이스 캐리", "38665162,11415616", "로디드 캐리 EMG(Ellestad 2024) + 요방형근 세침(McGill 1996) — 기울임 저항, 양측 공동수축; 한 손 그립도 좌우 같은 부하로 적는다 · 중량 미입력", LoadUnit.SECONDS, equipment=Equipment.DUMBBELL,
            muscles=arrayOf(Muscle.OBLIQUES to 1.0, Muscle.QUADRATUS_LUMBORUM to .8, Muscle.FOREARMS to .8, Muscle.ERECTORS to .6, Muscle.ABS to .4, Muscle.TRAPS to .4, Muscle.ABDUCTORS to .3, Muscle.GLUTES to .2, Muscle.QUADS to .15, Muscle.CALVES to .15)),
        p("single_leg_abdominal_press", "싱글레그 애브도미널 프레스", "https://www.mayoclinic.org/healthy-lifestyle/fitness/in-depth/core-strength/art-20546851", "손-무릎 누르기 직접 EMG 미확보 — Mayo Clinic 동작 정의(복직근·복사근 등척, 고관절 굽힘근)", LoadUnit.SECONDS, true,
            muscles=arrayOf(Muscle.ABS to .8, Muscle.OBLIQUES to .4, Muscle.HIP_FLEXORS to .4, Muscle.QUADS to .15)),
    )
    /**
     * 직접 추가한 종목의 프로필(§131 P9) — 앱 상태가 로드·추가 때 바꿔 끼운다. 고른 주 근육에만 역할 초기값을 주고 연구 출처는 없다
     * (화면이 "근육 부하는 고른 근육으로만 대략 추정" 이라고 밝힌다). iPhone 도 같은 소스(posture-core 복사본).
     */
    @kotlin.concurrent.Volatile var custom: List<ExerciseLoadProfile> = emptyList()
    fun customProfile(id: String, name: String, muscles: List<Muscle>, timed: Boolean): ExerciseLoadProfile = ExerciseLoadProfile(
        id = "custom:$id", name = name,
        weights = muscles.mapIndexed { i, m -> m to if (i == 0) 1.0 else .8 }.toMap(),
        sources = emptyList(), evidence = "직접 추가한 종목 — 고른 주 근육으로만 대략 추정, 연구 출처 없음",
        unit = if (timed) LoadUnit.SECONDS else LoadUnit.REP,
    )
    fun find(name: String) = all.firstOrNull { it.name == name || it.id == name || (name == "바벨 스쿼트" && it.id == "squat") }
        ?: custom.firstOrNull { it.name == name || it.id == name }
}

/** 세트 시점의 프로필과 관측 원본을 보존한다. null/미관측을 0회 판정으로 바꾸지 않는다. */
data class LoadSet(
    val id: String, val sessionId: String, val exerciseId: String, val endedAt: Long,
    val timeUncertaintyMs: Long = 0, val reps: Double = 0.0, val seconds: Double = 0.0,
    val unit: LoadUnit = LoadUnit.REP, val left: Double? = null, val right: Double? = null,
    val weightKg: Double = 70.0, val heightCm: Double = 170.0,
    val source: String = "session_count", val postureSetId: String? = null,
    val actualReps: Double? = null, val judged: Int? = null, val abstained: Int? = null,
    val accuracy: Int? = null, val modelVersion: Int = MuscleLoadCatalog.VERSION,
    val unknownSideSteps: Int = 0, val extraSteps: Int = 0,
)
data class MuscleDose(val muscle: Muscle, val side: String, val dose: Double)
data class LoadLedger(val sets: List<LoadSet>, val doses: Map<String,List<MuscleDose>>)
data class MuscleStatus(val muscle: Muscle, val left: Double, val right: Double, val lastAt: Long?) {
    val value get() = maxOf(left, right)
    val known get() = lastAt != null
}
data class LoadSnapshot(val muscles: List<MuscleStatus> = Muscle.entries.map { MuscleStatus(it, 0.0, 0.0, null) },
    val sets: List<LoadSet> = emptyList(), val calculatedAt: Long = 0) {
    val peak get() = muscles.filter { it.known }.maxByOrNull { it.value }
    val approximate get() = sets.any { it.source == "legacy" || it.timeUncertaintyMs > 0 }
}

object MuscleLoadEngine {
    const val RETENTION_DAYS = 7
    const val RETENTION_MS = RETENTION_DAYS * 24L * 60 * 60 * 1000
    fun doses(set: LoadSet): List<MuscleDose> {
        val p = MuscleLoadCatalog.find(set.exerciseId) ?: return emptyList()
        fun sane(v: Double) = if (v.isFinite()) v.coerceAtLeast(0.0) else 0.0
        val n = sane(set.actualReps ?: set.reps)
        val amount = if (set.unit == LoadUnit.SECONDS) sane(set.seconds) / 45.0 else n / 12.0
        if (amount == 0.0 && set.left == null && set.right == null) return emptyList()
        val kg = set.weightKg.takeIf { it.isFinite() && it in 20.0..350.0 } ?: 70.0
        val cm = set.heightCm.takeIf { it.isFinite() && it in 80.0..250.0 } ?: 170.0
        val body = if (p.bodyweight) ((kg / 70.0) * (cm / 170.0)).pow(.25).coerceIn(.85, 1.15) else 1.0
        // 사용자가 횟수를 고쳤으면 기존 좌우 관측과 섞지 않고 같은 단위로 다시 분배한다.
        val sided = set.unit == LoadUnit.PAIR || set.unit == LoadUnit.EACH
        val observed = sided && set.actualReps == null && set.left != null && set.right != null
        // unknown은 이미 좌우 카운트에 들어 있다. extra만 별도 미귀속 걸음으로 양쪽에 나눈다.
        val extra = set.extraSteps.coerceAtLeast(0) / 2.0
        val l = if (observed) (sane(set.left) + extra) / 12.0 else amount / if (set.unit == LoadUnit.EACH) 2.0 else 1.0
        val r = if (observed) (sane(set.right) + extra) / 12.0 else amount / if (set.unit == LoadUnit.EACH) 2.0 else 1.0
        return p.weights.flatMap { (m, w) -> listOf(MuscleDose(m, "L", l * w * body), MuscleDose(m, "R", r * w * body)) }
    }
    fun decay(hours: Double): Double = .35 * 2.0.pow(-hours.coerceAtLeast(0.0) / 6.0) + .65 * 2.0.pow(-hours.coerceAtLeast(0.0) / 24.0)
    fun score(load: Double) = 100.0 * (1.0 - exp(-load.coerceAtLeast(0.0) / 6.0))
    fun snapshot(sets: List<LoadSet>, now: Long, prepared: Map<String,List<MuscleDose>> = emptyMap(),
                 oldestAt: Long = now - RETENTION_MS): LoadSnapshot {
        // 재시도/세트 수정은 같은 ID의 최신 입력 한 건으로 계산한다.
        val retained = sets.associateBy { it.id }.values.filter { it.endedAt <= now && it.endedAt >= oldestAt }
        val sums = mutableMapOf<Pair<Muscle, String>, Double>(); val last = mutableMapOf<Muscle, Long>()
        retained.forEach { s ->
            val k = decay((now - s.endedAt) / 3_600_000.0)
            (prepared[s.id] ?: doses(s)).filter { it.dose > 0 }.forEach { d ->
                val key = d.muscle to d.side; sums[key] = (sums[key] ?: 0.0) + d.dose * k
                last[d.muscle] = maxOf(last[d.muscle] ?: 0, s.endedAt)
            }
        }
        return LoadSnapshot(Muscle.entries.map { m -> MuscleStatus(m, score(sums[m to "L"] ?: 0.0), score(sums[m to "R"] ?: 0.0), last[m]) }, retained.toList(), now)
    }
    /** 운동 가능/안전 판정이 아니다. 기록 부하와 덜 겹치는 후보만 제공한다. */
    fun recommendations(snapshot: LoadSnapshot, available: Set<Equipment>): List<ExerciseLoadProfile> {
        if (snapshot.peak == null) return emptyList()
        val values = snapshot.muscles.associate { it.muscle to it.value }
        return MuscleLoadCatalog.all.filter { it.equipment == Equipment.NONE || it.equipment in available }
            .filter { p -> p.primary.none { (values[it] ?: 0.0) >= 55.0 } }
            .sortedBy { p -> p.weights.entries.sumOf { (m,w) -> (values[m] ?: 0.0) * w } / p.weights.values.sum() }
            .distinctBy { it.primary.toSet() }.take(3)
    }
}

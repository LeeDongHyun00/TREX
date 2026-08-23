# AIHub 피트니스 자세 데이터 — 자세 판정 연구 파이프라인

TREX 자세 교정 기능의 판정 로직을 **데이터로 검증**하기 위한 오프라인 실험 코드.
(앱 코드가 아니라 연구용 Python. 결과물 `outputs/`는 git 제외.)

## 데이터
- AIHub `013.피트니스자세/1.Training/라벨링데이터` (기본 경로: `C:\Users\hp276\Desktop\trex\data\...`)
- 41종목 × 조건 조합(816타입) × 수행자, 클립당 16프레임 × 5뷰, 24관절 2D + 3D(cm)
- 조건 라벨: 종목별 3~5개 불리언 ("척추의 중립", "발과 무릎의 방향 일치" …) — 완전요인설계(2^k 타입)

## 설치
```bash
python -m pip install -r requirements.txt
```

## 1번 파서
```bash
python parse_labels.py            # 전체 (약 34k 클립, 멀티프로세스)
python parse_labels.py --limit 300 --out outputs_test   # 테스트
python parse_labels.py --skip-2d  # 3D/메타만 (빠름)
```
출력: `clips.parquet`, `conditions.parquet`, `kp3d.parquet`, `kp2d.parquet`, `joints.json`, `parse_report.json`

## 3D GT 품질 검사 (실험 B 전에 반드시)
검사 1 — 뼈 길이(몸통/어깨폭/골반폭/허벅지/정강이/상완/전완/목-등/등-허리)가 해부학적 범위를 벗어나거나
클립 중앙값 대비 ±30% 이탈하는 프레임.
검사 2 — L/R 일관성: 골반 좌우축 기준으로 어깨·귀 쌍이 반대편에 있는 프레임(좌우 스왑 → 신체좌표계 전방축 반전).
```bash
python qc_kp3d.py outputs
```
출력: `kp3d_frame_ok.parquet`(프레임 마스크), `qc_per_clip.csv`, `qc_report.md`

주의 — **프레임 간 점프(시간 평활도) 검사는 이 데이터에 쓸 수 없다.** 클립의 16프레임(img 번호 1,3,…,31)은
여러 렙에 걸친 성긴 샘플링이라(스쿼트 골반 높이 95→38→96→37…) 정상 동작도 프레임 간 수십 cm 이동한다.
실측: L/R 스왑은 프레임의 4.6%에 있으나 거의 전부 뼈 길이 검사와 겹침(추가 315프레임).

주의 — **"척추의 중립" 라벨은 종목마다 다른 편차로 연기됐다** (`description` 필드):
말림(스쿼트·데드·로우·컬·OHP), 옆으로 갸우뚱=측굴(굿모닝·런지류), 좌/우 기울기(스티프 데드),
허리 반동(라잉 트라이셉스), 허리 힘빼기(푸시업). 종목별 최적 피처가 다른 이유이며, 척추 규칙은 종목별로 달라야 한다.

실측(2026-08): 전체 프레임의 **18%가 불량**. 서 있는 종목은 1~6%로 양호하지만
바닥/누운 종목(크런치·푸시업·플랭크·레그레이즈·힙쓰러스트·시저크로스·Y-Exercise 등)은 **73~92% 불량** —
이 종목들의 3D GT는 신뢰할 수 없다(리그가 선 자세용). 벤트오버 계열(굿모닝 27%, 바벨 로우 19%)은 중간.
`features.py`는 이 마스크를 자동 적용한다(불량 프레임 NaN, 양호 프레임 <8 클립 제외).

## 실험 B — 각도 규칙 엔진의 상한 성능
GT 3D 스켈레톤 → 각도/기하 피처(`features.py`) → 조건 라벨 예측 AUC (수행자 GroupKFold).
```bash
python experiment_b.py --jobs 4 --recompute-features
python spine_check.py outputs      # 실험 D-lite: '척추의 중립' vs 척추 폴리라인 피처
```
출력: `experiment_b_results.csv`, `experiment_b_summary.md`, `spine_check.md`

QC 적용 전 결과는 `*_raw.*` 로 보존 (아티팩트 비교용).

- `rule_auc_cv`: 단일 피처 + 임계값 규칙의 정직한 CV 성능
- `lr_auc_cv`: 선형 결합 규칙
- `gbm_auc_cv`: 비선형 상한

해석: 여기서 AUC가 낮은 조건은 포즈 추정기가 완벽해도 "각도 규칙"으로는 못 잡는 조건 →
다른 관측(시계열·기구·영상 기반) 또는 스코프 제외 대상.

## 척추 하위유형 라벨 (description 기반)
```bash
python spine_subtypes.py outputs      # → spine_subtype.parquet, spine_subtype_report.md
```
'척추의 중립=false' 를 description 키워드로 flexion / lateral / extension / lumbar_swing / lumbar_sag / forward_lean /
cervical(시선 조건이 없는 런지류에서 '왼쪽·하늘 보고'를 척추 비중립으로 코딩한 관행) / unspecified 로 분류. 98% 특정.

## 룰엔진 v0 (조건별 물리 피처 화이트리스트 단일 규칙)
```bash
python rule_engine_v0.py outputs               # → rules_v0.json / rules_v0.csv / rule_engine_v0.md
python rule_engine_v0.py outputs --merge-only  # 실험 B 재실행 후 gbm/lr 비교 컬럼만 갱신
```
- 조건명 키워드 → 허용 피처 패밀리(`COND_RULES`, `FAMILIES`), 척추는 하위유형별 패밀리(`SPINE_FAMILIES`)
- 수행자 GroupKFold 로 단일 피처 + Youden 임계값 규칙 검증; 같은 절차를 전체 피처(비제약)와 MediaPipe-가능 피처만으로 반복
- `rules_v0.json` 의 각 항목: feature / sign / threshold / cv_auc / cv_balacc / mediapipe{feature, rule, cv_auc, computable}
- 라벨명과 연기된 편차가 다른 조건(예: 행잉 레그 레이즈 '어깨-귀 거리' = 좁은 그립, OHP '전완 수직' = 팔꿈치 내밀기)은
  `COND_RULES` 주석에 근거를 남기고 패밀리를 보강함
- `mediapipe_computable()`(features.py): Back/Waist 기반 spine_*, neck_angle, shoulder_neck_gap 은 MediaPipe 33점으로 계산 불가

## 실험 A — MediaPipe(단일 뷰) vs GT
```bash
python mp_sample.py                 # 샘플 목록 (종목당 60클립, 3D불량 종목 20클립, 16프레임×5뷰) + tar→Day 매핑
python mp_infer.py --workers 6      # tar 미해제: 헤더 인덱싱 후 필요한 파일만 seek-read → MediaPipe → landmarks_<tar>.parquet (재개 가능)
python experiment_a.py              # 2D 정확도 / 3D 피처 충실도 / 규칙 전이(뷰별·멀티뷰 융합) → experiment_a_summary.md
python experiment_a_refit.py        # MediaPipe 피처 위에서 규칙 재적합(제품 시나리오) → expA_refit_summary.md
```
- 뷰 기하(GT 2D 어깨 투영비로 판별): C=정면, B/D=전방 사선 ±40°, A/E=후방 사선 ±40° — 순수 측면 뷰 없음
- MediaPipe world 좌표는 y 아래가 + 이므로 y/z 부호 반전 후 cm 변환, Neck=어깨중점, Palm=pinky/index 중점, Foot=foot_index
- 모델: `models/pose_landmarker_full.task` (Google 공식 저장소, 9.4MB; gitignore)
- 디스크: 원시 이미지를 디스크에 쓰지 않음. tar당 인덱스 parquet(수 MB) + 결과 parquet(수 MB)만 생성
- tar 1개 = 촬영일 1개 (`outputs/mp/tar_days.json`). Day37/38 은 2D 라벨 없음 → 미사용. Day28 은 3D 전량 불량(리그 캘리브레이션 실패일) → QC 에서 제외됨

## 앱 포팅용 규칙 확정 (rules_mp_v0 → v0.1)
```bash
python experiment_a_refit.py --mirror-safe   # 미러 불변 화이트리스트 재적합 → expA_refit_mirror.csv
python export_rules_mp.py                    # expA_refit(+_mirror).csv → rules/rules_mp_v0.json (version mp_v0.1), rules_mp_v0.md
cp rules/rules_mp_v0.json ../../app/src/main/assets/posture/rules_mp_v0.json
```
- 전방 반구(B/C/D) 최적 단일 뷰의 MediaPipe 재적합 규칙. 등급 ship(AUC≥0.85) / beta / exclude(사유 기록)
- v0.1: 미러 불변(좌/우 카메라 위치 무관) 규칙 우선 채택, 교체된 비제약 규칙은 `alt_rule` 보존 — ship 59 / beta 12 / exclude 70, 미러 불변 ship 53
- 포팅 명세: [KOTLIN_PORTING_SPEC.md](KOTLIN_PORTING_SPEC.md) — 좌표 변환·관절 매핑·신체좌표계·피처 공식·집계 창·규칙 평가·촬영 가이드·재보정 절차·앱 구현 현황·재보정 툴체인

## 재보정 툴체인 (명세 §14)
```bash
python demo_setlogs_from_aihub.py            # (검증용) AIHub MP 결과 → 앱 세트 로그 스키마 + labels.csv
python calibrate_from_logs.py --logs <posture_logs|*.jsonl> --labels labels.csv --rules rules/rules_mp_v0.json --out outputs/calib --suggest
```
- 앱 쪽 작성기: `app/src/main/java/com/example/trex_kotlin/posture/PostureSetLog.kt` (+ `PostureSetLogTest`)
- 로그 + 코치 라벨 → 피처·방향 고정, 임계값 Youden 재적합(수행자 GroupKFold) → `rules_calibrated.json` + 리포트

## 룰엔진 v1 탐색 (명세 §15)
```bash
python rule_engine_v1.py     # (A) 2-피처 규칙(깊이-2 트리) vs 단일, (B) 개인 기준선 오프셋 → rule_engine_v1_summary.md
```
결과: (A) 2-피처는 단일보다 나쁨(Δ −0.020) → 채택 안 함. (B) 개인 기준선은 level 피처(mean/min/max)에서만 개선 → 규칙 JSON `personal_baseline.eligible` 주석으로 반영.

## 개인화 실험 4종 (명세 §16)
```bash
python personalization_experiments.py [--summary-only]   # 오라클 상한 / 임계값 분산 분해 / 체형 조건화 / 기준선 오염 → personalization_summary.md
```
결과: 개인별 임계값 재적합(A)은 정직한 이득 0(+0.002) → 기본 경로 제외; ICC 0.66 으로 개인차는 실재하나 level 피처에 국한 → 기준선 정규화(B) 로 흡수;
체형 조건화는 R² 0.08 로 기각; 기준선 오염은 1개까지 강건, 5세트 중앙값 권장.

## 촬영 프로토콜 (명세 §17)
```bash
python calibration_protocol.py    # 표본 크기 곡선·기준선 k 곡선 → outputs/CALIBRATION_PROTOCOL.md
```
결론: 재보정은 **종목당 30세트(최소 12) × 여러 사람(3~6명+)**, 세트마다 조건별 정상/위반 무작위 절반 배정.
개인 기준선은 **eligible 6종목만, 정자세 3세트**.

## 다음 단계 (예정)
- 랩 화면 "로그 저장 ON"으로 자체 촬영(여러 사용자) → 내보내기 → 코치 라벨 `labels.csv` → `calibrate_from_logs.py` → 규칙 JSON 갱신·에셋 반영
- 앱: 기준선 세트(정상 자세 5세트 중앙값) UI + `personal_baseline.eligible` 규칙의 사용자 기준선 보정 (§15·§16)
- 실험 C(뷰 전이)는 실험 A 의 뷰별 분석으로 대체됨

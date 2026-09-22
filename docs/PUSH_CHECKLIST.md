# push 체크리스트 — `feature/food-recognition-v2` (2026-09-23)

저장소는 **공개**(github.com/LeeDongHyun00/TREX)다. 올리기 전에 한 번 더 확인할 것을 적어 둔다.

## 무엇을 올리나

브랜치 `feature/food-recognition-v2` 는 `feature/posture-coach-reliability`(이미 origin 에 있음) 위에 **음식 인식 관련 커밋만** 쌓았다. push 하면 그것만 올라간다 — 조원의 자세 작업은 이미 올라가 있어 중복되지 않는다.

변경 규모: **40파일, +7,001 / −26**

| 영역 | 내용 |
|---|---|
| `app/src/main/java/.../food/` | `FoodDetector` — 모델 로딩(mmap)·letterbox 전처리·NHWC/NCHW 자동·추론 |
| `app/src/main/java/.../PhotoFoodSheet.kt` | 사진 기록 시트 전체(촬영·갤러리·분석·결과·라이트박스) |
| `app/src/main/java/.../TrexData.kt` | `foodDatabase` 350종, `approximateNutritionNames`, `foodNameAliases` |
| `app/src/main/java/.../TrexAppState.kt`, `TrexStore.kt`, `Sheets.kt` | 직접 등록 음식(기기 저장·검색·별칭) |
| `app/src/main/assets/models/` | `yolov8n_food.tflite` 3.6MB + `food_labels.txt` 342종 |
| `app/src/test/` | `FoodLabelDatabaseTest`, `CustomFoodTest`, `FoodAliasTest` (총 284건 통과) |
| `training/` | 학습·평가 Colab 노트북, `local_prep/` 전처리 도구 |
| `docs/` | 설계 근거·평가 결과·커버리지 측정·논의 문서 |

가장 큰 새 파일은 모델 3.6MB 다. 저장소에는 이미 자세 모델 9.0MB·5.5MB 와 30MB json 이 있어 특별히 무겁지 않다.

## 무엇을 올리지 않나 (확인 완료)

- **AI Hub 원천 이미지·데이터셋** — 저장소 밖(`C:\Workspace\TREX\aihub74_raw`)에만 있다. `dataset.tar`(4GB), `eval_*.tar`, 원천 zip 전부 해당.
- **AI Hub 영양DB 400종 원표** — `class_map.csv`/`class_map.json` 은 `.gitignore` 에 있다. 공개 저장소에 두면 승인 절차를 우회시킬 소지가 있어 뺐다(근거: `training/local_prep/README.md` 출처 절). 앱이 쓰는 342종 파생값은 유지.
- **사용자 식단 사진 16장** — 개인 사진이라 저장소에 넣지 않았다. 평가 패키지(`eval_real.tar`)도 로컬에만 있다.
- **AI Hub API 키** — 전체 히스토리와 커밋 메시지를 검사했다. **출현 0건.** 스크립트는 전부 인자로 받는다(`dl.sh "$3"`).

## 확인 완료 (2026-09-23 04:30, 이 세션에서 실행함)

| 항목 | 결과 |
|---|---|
| 미커밋 변경 | 없음 |
| 올라갈 커밋 | 전부 음식 인식 작업 (`git log --oneline origin/feature/posture-coach-reliability..HEAD` 로 확인) |
| API 키 출현 (`A799AD2A`) | **0건** |
| JVM 유닛 테스트 | 42클래스 **284건 전부 통과** (실패 0 · 오류 0) |

테스트는 `app/` 에 변경이 없어 gradle 이 UP-TO-DATE 로 건너뛰었다. 결과 XML 을 직접 집계한 수치다.
**push 직전에 다시 돌릴 필요는 없다** — 그 사이 `app/` 을 고쳤다면 그때 돌린다.

## 직접 확인할 것 (선택)

```bash
cd C:/Workspace/TREX/TREX_UI

# 1) 미커밋 변경이 없는지
git status --short

# 2) 올라갈 커밋 확인 — 음식 인식 작업만 있어야 한다(자세 커밋이 섞이면 분기점이 틀린 것)
git log --oneline origin/feature/posture-coach-reliability..HEAD

# 3) 키가 섞이지 않았는지 (0 이어야 한다)
git log -p origin/feature/posture-coach-reliability..HEAD | grep -c "aihubapikey.*[A-F0-9]\{8\}-"

# 4) 테스트
export JAVA_HOME="C:/Users/cys17/.jdks/jbr-21.0.11"
./gradlew.bat :app:testDebugUnitTest
```

## push

```bash
git push -u origin feature/food-recognition-v2
```

그다음 PR: https://github.com/LeeDongHyun00/TREX/pull/new/feature/food-recognition-v2

**base 는 `feature/posture-coach-reliability`로 잡는다** — `main` 으로 잡으면 조원의 자세 작업 65커밋까지 diff 에 들어와 리뷰가 불가능해진다.

### PR 설명 초안

```
## 무엇

온디바이스 음식 인식을 실제로 동작하는 상태로 만들었다. 리디자인에서 "사진 분석은
아직 연결되지 않았어룡" 스텁이던 사진 식단 기록을 촬영→인식→확인→저장 흐름으로 채우고,
AI Hub 74번으로 342클래스 모델을 학습해 탑재했다.

## 주요 변경

- 사진 기록: CameraX 촬영 / 갤러리 5장 / 온디바이스 YOLOv8n 추론 / 끼니·수량 확인 후 저장.
  사진은 기기 밖으로 나가지 않고 디스크에도 쓰지 않는다(INTERNET 권한 없음).
- 모델: 342클래스 INT8 3.6MB. AI Hub 74번 Validation 6묶음 68,176장으로 학습.
- 영양 DB: 350종(실측 342 + 일반 식품 8). AI Hub 영양DB 1인분 실측값.
- 직접 등록 음식: 기본 DB 에 없는 음식을 기기에 저장하고 재사용. 실사용 음식의 33%가
  AI Hub 에 아예 없다는 측정 결과에 따른 것이다(피자·덴푸라·가라아게 등 한식 데이터셋이
  구조적으로 못 담는 것들).
- 검색 별칭 16개: 흰쌀밥→쌀밥, 돈까스→돈가스 등. 사용자 표현과 클래스명 불일치를 메운다.
- 학습·평가 파이프라인: Colab 노트북 2개 + 로컬 전처리 도구(AI Hub 는 해외 IP 다운로드를
  막아 PC 에서 받아야 한다).

## 검증

- JVM 284건 통과, assembleDebug 성공(JDK 21).
- 실기기(SM-F956N) 설치·촬영·인식 확인.
- held-out(밥 8종 399장): Top-1 73%, 검출률 73%.
- 실사용 사진 16장: 맞힐 수 있는 것의 36% 검출, 사진당 손볼 횟수 1.8회.

## 알려진 한계

- 한 상에 여러 음식이 작게 찍힌 사진에서 검출이 크게 떨어진다(73%→36%). 학습 데이터가
  "그릇 하나가 화면을 채우는" 사진이라 크기·맥락이 어긋나는 것으로 보인다. 타일 추론
  실험이 다음 후보다 — 재학습 없이 앱 후처리만으로 검증 가능하다.
- 실사용 음식의 33%는 AI Hub 데이터셋에 없다(피자·덴푸라·가라아게 등). 직접 등록의 몫이다.
  반대로 클래스가 있는 67% 중에서도 실제 검출은 36%다 — 이 간격이 구도 문제다.
- 근거와 수치는 docs/FOOD_EVAL_RESULTS.md, docs/FOOD_COVERAGE_FINDINGS.md 참조.

## 리뷰 시 봐 주었으면 하는 곳

- `FoodDetector.runInference` — 출력 파싱과 임계값 처리
- `PhotoFoodSheet` 의 실패 분기(모델 부재·추론 오류·검출 0건)가 각각 다른 문구인지
- `foodNameAliases` 에 빠진 별칭이 있는지
```

## 주의

`git push` 는 공개 저장소에 즉시 반영된다. 위 확인을 마치고 직접 실행할 것 — 이 문서는 준비까지만 해 둔 것이다.

# A5 — 문헌·기술 조사: 폰 카메라로 스쿼트 변수를 얻고 해석하기 (2026-09-25)

범위: 웹 문헌·공식 문서·소스 코드만 봤다(데이터 실측은 A1~A4 몫). **확신도**: 상 = 1차 출처 본문·초록에서 직접 확인, 중 = 리뷰·2차 출처·일부만 확인, 하 = 검색 요약만 봄, **미확인** = 찾지 못함. **[계산]** = 공개 수치로 우리가 계산한 값(가정을 적어 둠). 대괄호 번호는 문서 끝 출처 목록.

## 0. TREX 에 주는 결론
1. **추론 간격 300 ms(3.3 Hz)로는 스쿼트 국면을 담지 못한다.** 맨몸 스쿼트는 하강·상승이 각각 약 1.0 s다[44]. 3.3 Hz면 국면마다 약 3샘플이다. 고중량 상승의 sticking region은 0.21 s라[45] 샘플이 0~1개 걸린다. 3.3 Hz의 나이퀴스트 주파수는 1.67 Hz인데, 스쿼트 운동학 연구는 120~500 Hz로 받아 10~12 Hz로 거른다[44][46]. 최소 10 Hz, 가능하면 카메라 프레임률(30 Hz)을 권한다. 걸림돌은 추론 비용보다 발열일 수 있는데, 발열 수치는 **미확인**이다(§7).
2. **정면 2D '무릎 안쪽 모임'은 3D 외반각이 아니다.** 한 다리 스쿼트에서 FPPA와 3D 무릎 외반의 상관은 r = 0.13~0.31이다[20][21][23]. 대신 **개인 기준 대비 변화량**은 믿을 만하다. MediaPipe 정면 무릎 외반각은 절대 오차가 약 19°였지만, 초기 접지 대비 변화량의 편향은 0.18°였다[24](착지 과제). 시작 자세를 기준으로 삼는 지금 설계(§62a)와 맞는다. 문구는 "무릎이 안쪽으로 모였습니다"처럼 위치로 말하고, "외반 N°"처럼 각도로 말하지 않는다.
3. **측면 2D는 무릎·몸통 굴곡 측정에 타당하다**(r = 0.51~0.94[21][23]). 정면 단안에서는 수직 오차가 커진다[14]. 깊이·숙임·hip rise는 측면이나 사선에서 재는 편이 유리하다. 단, 측면에서는 먼 쪽 다리가 가려 그 다리의 오차가 두 배가 된다[19]. MediaPipe 측면 단안에서 가려진 무릎각 RMSE는 25.1°, 보이는 쪽은 10.7°였다[16].
4. **뒤꿈치 들림과 허리 말림을 영상으로 검출할 수 있다는 근거는 없거나 약하다.** MediaPipe의 발목·발 랜드마크가 부정확하다는 보고가 세 건 있다[17][18][19]. 모델 입력(256×256)에서 발 부위 해상도는 약 8~10 mm/px라[계산] 1~2 cm 들림은 1~2 px에 불과하다. 허리는 등 중간에 키포인트가 없어 둥근 등과 휜 등을 구분하지 못했고[28], 물리치료사도 영상에서는 골반 후방 경사가 34° 이상이어야 알아봤다[27]. 그래서 카탈로그 #6의 "옆면 필요"는 **"옆면이어도 MediaPipe에는 요추점이 없어 못 봄"**으로 고쳐 적는 편이 정직하다. #7도 옆면으로 바꾸기 전에 실측이 필요하다.
5. **world landmarks의 z는 판정에 쓰지 않는다.** 독립 평가에서 깊이 오차가 영상 평면 오차의 2~3배였다(e_z 108 mm, e_x 50 mm)[14]. 모델카드도 "미터 단위의 정확한 깊이가 필요한 앱"은 쓰임새 밖이라고 적었다[11].
6. **전면 카메라가 받는 빛은 후면 주 카메라의 약 1/4.6~1/6.3이다**[계산, 9]. 실내에서는 노출이 길어져 블러가 생긴다. AE 범위가 [12, 30] fps면 노출이 최대 약 83 ms까지 늘 수 있다[7]. 이때 0.72 m/s[45]로 움직이는 관절은 6 cm(2.5 m 거리에서 약 12 px) 번진다[계산]. MediaPipe 학습 이미지는 전부 **후면** 카메라로 찍었다[11](전면에서 생기는 차이의 크기는 미확인).
7. **분석 프레임은 좌우 반전되지 않는다.** CameraX ImageAnalysis는 미러 모드를 지원하지 않는다[3]. 그래서 랜드마크의 좌우는 해부학적 좌우이고, 반전은 화면 오버레이에만 필요하다(`PostureLive.kt`가 이미 그렇게 한다).
8. **깊이 센서는 선택지에서 뺀다.** ARCore 기기 목록에서 ToF 표기는 11건으로, Depth API 지원 약 1,150건 가운데 2019~20년 플래그십뿐이다[42]. depth-from-motion은 폰이 움직여야 깊이를 내고[40], 전면 카메라에서는 ARCore 추적이 멈춘다[41].
9. **확인된 상용 앱은 모두 정면에서 약 2 m(6~8 ft) 거리로 찍는다.** 스쿼트에 측면 촬영을 요구하는 공식 문서는 **찾지 못했다**.
10. **REHAB24-6의 스쿼트 오류 유형과 카메라 높이·각도는 공개 자료에 없다**(논문 폐쇄 접근). 3D GT와 2D 투영이 있으니 PnP로 카메라 자세를 역산하는 편이 빠르다. 측면 스쿼트는 cam18에 98회 있다(로컬 확인, §8).

## 1. 전면 vs 후면 카메라 (안드로이드)
| 기기 (GSMArena[9]) | 후면 주 | 후면 초광각 | 전면 |
|---|---|---|---|
| Galaxy S24 | 50 MP f/1.8 24 mm 1/1.56″ PDAF·OIS | 12 MP 13 mm 120° | 12 MP f/2.2 26 mm 1/3.2″ **듀얼픽셀 AF**, 4K30/60 |
| Galaxy A55 | 50 MP f/1.8 1/1.56″ | 12 MP 123° | 32 MP f/2.2 26 mm 1/2.74″ (AF 표기 없음), 4K30 |
| Galaxy A35 | 50 MP f/1.8 1/1.96″ | 8 MP 123° | 13 MP f/2.2 1/3.06″ (AF 표기 없음) |
| Pixel 8 | 50 MP f/1.7 25 mm 1/1.31″ | 12 MP 126° AF | 10.5 MP f/2.2 20 mm 1/3.1″ (AF 표기 없음), 4K30/60 |

- **화각 [계산]** (4:3 프레임, 35 mm 환산 대각 기준): 26 mm는 장변 67°·단변 53°, 20 mm는 82°·66°, 13 mm는 106°·90°다. 키에 여유를 더한 2.0 m를 담는 최소 거리는 카메라가 몸 중앙 높이일 때 26 mm 세로 1.5 m, 26 mm 가로 2.0 m, 13 mm 가로 1.0 m다. 바닥에 세운 폰은 위로 기울여야 하므로 이보다 멀어야 한다.
- **고정초점**: Camera2 `LENS_INFO_MINIMUM_FOCUS_DISTANCE`가 0이면 고정초점이라 기기별로 런타임에 확인할 수 있다[1](상). 중저가 기기의 전면 카메라에는 AF 표기가 없다[9]. [계산] f≈3.4 mm, f/2.2, 허용 착란원 3.8 µm로 가정하면 과초점 거리가 약 1.4 m라 0.7 m부터 무한대까지 초점이 맞는다. 즉 2~3 m 거리에서는 초점보다 노출과 잡음이 문제다(가정에 의존).
- **저조도와 노출**: `aeTargetFpsRange`는 AE가 노출을 맞추려고 프레임률을 조정하는 범위다[1]. CameraX 팀의 예시는 [12, 30] fps이고, 어두우면 fps가 내려간다(2023-05)[7]. Android 15 이상의 Low Light Boost는 최저 10 fps까지 내려가고 모션 블러가 늘어난다고 명시돼 있다(현재 Pixel 10만 지원)[8]. 확신도 상.
- [계산] **빛의 양**: S24 전면은 주 카메라 대비 센서 면적 1/4.2에 조리개 차 1/1.49가 곱해져 약 1/6.3, A55는 약 1/4.6이다. 같은 밝기를 얻으려면 노출을 5~6배 늘리거나 ISO를 2.2~2.6 스톱 올려야 한다.
- [계산] **블러**: 관절 속도를 0.72 m/s(6RM 바 최고 속도[45])로 두면 노출 33/83/100 ms에서 2.4/6.0/7.2 cm가 번진다. 2.5 m 거리, 26 mm 화각(5.2 mm/px)에서 약 5/12/14 px다.
- **초광각(CameraX)**: `CONTROL_ZOOM_RATIO`를 1 미만으로 주면 초광각으로 넘어간다(문서 예시 0.5x, Android 11 기기부터)[1][6]. CameraX에서는 `getZoomState().minZoomRatio < 1`이면 `setZoomRatio`로 바꿀 수 있다. 다만 **멀티카메라 API를 노출한 기기에서만** 되고, 나머지 기기는 최소값이 1.0이다(CameraX 팀, 2025-01)[6]. 물리 카메라를 직접 지정하는 API는 `Camera2Interop` physical ID(1.1.0-beta02), `CameraSelector.Builder.setPhysicalCameraId`·`PhysicalCameraInfo`(1.4.0-alpha05)이고, 화각 비교에는 `getIntrinsicZoomRatio`(1.3.0-alpha03)를 쓴다[5]. 제조사가 초광각을 자사 앱의 private API로만 여는 경우도 있다[2]. 확신도 상.
- **왜곡 보정 `DISTORTION_CORRECTION_MODE`**(API 28): 값은 OFF/FAST/HIGH_QUALITY다. **지원하는 기기에서는 기본으로 켜져 있지만 지원은 의무가 아니다**(미지원 기기는 OFF만 가짐). YUV 같은 처리된 출력에만 적용되고 RAW에는 적용되지 않는다. FAST는 보정하면 프레임률이 떨어지는 경우 OFF와 같을 수 있다[1](상). 렌즈 왜곡 계수 `LENS_DISTORTION`이 제공되면 영상 대신 랜드마크 좌표만 보정할 수도 있다[1]. 삼성 S10/S20이 이 키를 지원하지 않는다는 포럼 보고가 있다(하)[10]. 초광각 가장자리 왜곡이 관절각에 주는 영향의 크기는 **미확인**이다.
- **미러링·해상도**: ImageAnalysis의 `setMirrorMode`는 UnsupportedOperationException을 던지고 서피스는 MIRROR_MODE_UNSPECIFIED로 붙는다[3]. PreviewView는 전면 카메라의 서피스 미러를 따로 변환해 처리한다(소스 주석)[4]. 분석 해상도를 지정하지 않으면 640×480이 우선 선택된다[3]. 확신도 상.

## 2. MediaPipe Pose Landmarker (BlazePose GHUM)
- **모델카드[11]**(상):
  - 크기는 Lite/Full/Heavy 3/6/26 MB다. Pixel 3에서 Lite는 CPU 약 44·GPU 약 49 FPS, Full은 약 18·40 FPS, Heavy는 약 4·19 FPS다.
  - PDJ(= PCK@0.2, 몸통 대각 기준, 후면 폰 사진 1,400장) 평균은 87.0/91.8/94.2 %다.
  - 쓰임새 밖으로 명시된 조건은 **4 m(14 ft) 초과 거리**, 머리가 안 보이는 경우, 미터 단위의 정확한 깊이가 필요한 앱이다.
  - z는 GHUM 합성 모델을 맞춰서 얻은 값이라 미터 단위가 아니고 스케일만 맞는다.
  - 입력은 256×256 ROI(전신을 두른 정사각형에 25 % 여백)다. 위치·크기 10 %, roll 8°까지 흔들려도 견딘다. 조명·잡음·움직임이 나빠지면 jitter가 는다.
- **GHUM Holistic 논문[12]**(상): 요가 영상에서 사람과 카메라 거리 **2~4 m**로 평가했다. 2D mAP는 68.1/62.6/45.0, 3D MAE는 36/39/45 mm다(Heavy/Full/Lite). 단, 3D 정답이 모션캡처가 아니라 GHUM을 맞춘 값이다. 지연은 Pixel 4 CPU 25/40/147 ms, GPU 8/9/22 ms(Lite/Full/Heavy)다.
- **모션캡처와 독립 비교한 Physio2.2M[14]**(상):
  - 조건: 웹캠 30 Hz, 거리 3~3.5 m, 높이 27·95 cm, 정면과 측면.
  - BlazePose World Heavy: e_x 50 mm, e_y 58 mm, **e_z 108 mm**, MPJPE 146 mm, 무릎 굴곡 MAE 17.2°, 검출률 98.8 %.
  - 추정기 11개 전체: 무릎 MAE가 2D 9.3~21.9°, 3D 14.1~25.8°였다. 3D 방법은 모두 깊이 오차가 평면 오차의 2~3배였고, **정면 뷰에서는 수직 오차가 수평 오차보다 훨씬 컸다**.
- **촬영 방향 의존**:
  - Dill 2023: 정확도가 시야각과 운동 종류에 크게 좌우된다(초록)[15].
  - Dill 2024: 폰 2대(정면·측면, 삼각대 약 130 cm)로 스쿼트(정상/등 굽힘/오른쪽 쏠림)를 찍었다. 정면 기반 3D가 측면 기반보다 나빴는데, 좌우 대칭 동작이라 깊이 정보가 빠지는 손해가 더 컸다. 측면 단안의 무릎각 RMSE는 보이는 쪽 10.7°, 가려진 쪽 25.1°였다. z축 잡음이 크고, MediaPipe는 표준 키를 가정해 깊이를 맞춘다[16].
  - 사선(45°) 뷰에 대한 MediaPipe 수치는 **미확인**이다. BlazePose로 저항운동을 평가한 연구는 비시상 뷰가 2D 관절각을 왜곡한다고 보고했다(초록, 중)[31].
- **발끝 쪽 랜드마크**(heel 29/30, foot_index 31/32[13]): 랜드마크별 공식 정확도는 **미확인**이다[11]. 폰+MediaPipe 보행 연구에서 무릎 굴곡 MAE는 3.2~4.1°(IMU 기준)였지만 발목 배굴에는 계통 오차가 있었고 일치도가 낮았다[17]. OpenPose와 MediaPipe 모두 발목 랜드마크를 잘못 짚었다[18]. 단일 카메라 마커리스에서 발목 LoA는 ±12°였다[19].
- **[계산] 해상도**: 키 1.75 m인 사람의 ROI(여백 포함 약 2.2~2.6 m)가 256 px로 줄어들어 모델 입력에서는 약 8.5~10 mm/px다. 26 mm 가로 모드, 2.5 m 거리에서 사람은 약 336 px로 이미 256 px보다 크다. 그래서 640×480보다 해상도를 올려도 전신 정확도는 거의 좋아지지 않는다. 4 m에서는 약 210 px로 모자란다.

## 3. 스마트폰 2D 영상으로 잰 스쿼트 운동학의 타당도
| 변수 | 근거 | 수치 | 확신 |
|---|---|---|---|
| FPPA vs 3D 외반 | 메타분석 16편[20] | 한 다리 스쿼트 r = 0.127(상관 없음), 착지 r = 0.619 | 상 |
| 〃 | 한 다리 스쿼트, n = 26[21] | 정면 무릎 r = 0.308, 시상면 r = 0.51~0.93 | 상 |
| 〃 | 슬개대퇴 통증 여성[22] | FPPA와 고관절 내전 r = 0.32~0.38, 무릎 외회전 r = 0.48~0.55. "3D 회전 정량화에는 쓰지 말 것" | 상 |
| 〃 | 2D 포즈 추정, 청소년 한 다리 스쿼트[23] | 시상면 r = 0.68~0.94, 정면 무릎 r = 0.20(3D 대비). 2D 수기 분석과는 r = 0.95 | 상 |
| 〃 | MediaPipe 2D, 한 발 착지(정면 5 m, 100 Hz)[24] | 절대 오차 18.8~19.7°(편향 −19.3°). **초기 접지 대비 변화량은 편향 0.18°**, r = 0.55~0.76 | 상 |
| 〃 시점 | 한 다리 스쿼트 3D 240 Hz[25] | 무릎이 가장 안쪽인 시점은 하강 구간 59.0 %, 외반각이 최대인 시점은 42.4 %. 서로 다른 순간이다 | 중 |
| 측면 몸통·무릎·발목 | OpenPose, 양발 스쿼트 우측면, n = 20[26] | ICC가 몸통·무릎·발목은 거의 완벽~상당, 고관절은 보통(fair). 무릎·발목에는 고정 편향 | 상 |
| 가려진 쪽 다리 | 단일 카메라 보행[19] | 가려진 쪽 시상면 엉덩이·무릎 오차가 두 배. 정면 엉덩이·무릎 편향 −4.6~1.6° ± 3.7~4.2°(마커 방식 오차 수준), 발목 LoA ±12° | 상 |
| 뒤꿈치 들림 | 영상 검출 검증 | **미확인**. Myer의 BSA는 관찰 항목으로만 둔다[30] | — |
| 허리 말림 | 치료사의 영상 평가 vs IMU[27] | 골반 후방 경사가 **34° 이상**이어야 알아봄 | 상 |
| 〃 | 스쿼트 7클래스 분류[28] | 둥근 등과 휜 등을 혼동. 등 중간에 키포인트가 없다 | 상 |
| 권장 촬영 방향 | Myer 2014[30] | 앞·뒤·옆 3방향 녹화를 권장. 외반은 앞에서 "어느 국면에서든" 보인다 | 상 |

- **자세 오류 분류 연구**:
  - Ogata 2019[28]: 7클래스(정상, 무릎 안쪽, 둥근 등, 휜 등, 고개 들림, 얕음, 무릎 앞으로)를 3D 포즈(HMR) 거리행렬과 1D CNN으로 분류했다. 정확도는 단일 인물 81.1 %, 7명 88.9 %, YouTube 78.3 %다. 실패 사례를 보면, 무릎 모임은 **결정적 순간의 한 프레임**을 놓치면 함께 놓쳤고, 사람이 멀거나 몸 일부만 보일 때도 틀렸다.
  - Fitness-AQA[29]: 체육관 영상에서는 기성 2D/3D 포즈 추정기가 카메라 각도, 기구에 의한 가림, 조명, 옷 때문에 잘 작동하지 않았다(초록).
  - Kaia Motion Coach[32]: 무릎·고관절 골관절염 환자 24명, 운동 6종에서 앱과 치료사의 일치율이 82.8 %로, 치료사끼리의 일치율 83.3 %보다 떨어지지 않았다.
  - MediaPipe 기반 스쿼트 오류 분류기를 **다른 피험자·다른 환경**에서 독립 검증한 연구는 **찾지 못했다**.

## 4. 상용 앱의 카메라 배치 가이드
| 앱 | 카메라·배치 | 측면 요구 | 피드백 | 출처(확신) |
|---|---|---|---|---|
| Kaia Motion Coach | 전면 카메라. 폰을 바닥에 두고 **약 2 m** 떨어져 살짝 기울임. 설정 화면이 설 위치를 안내 | 언급 없음 | 실시간 음성·시각 교정 | [32] 상 |
| Kemtai | 웹캠 앞 **약 8 ft**(키 191 cm는 9 ft). 전신이 인식돼야 시작 | 언급 없음 | 반복마다 1~100점, 44개 점 추적. 바닥 운동은 인식이 약함 | [33] 중 |
| Zenia | 전면 카메라, **약 6 ft(2 m)** | 언급 없음 | 2D 골격을 초록/빨강으로 표시. 큰 움직임만 잡음 | [34] 중 |
| Tempo Move | iPhone **전면 TrueDepth**, TV 앞 도크, 공간 6×6 ft | — | 자세·반복·무게 | [35] 중 |
| Onyx | iPhone TrueDepth | 미확인 | 반복 카운트, 자세 이탈·속도 알림 | [36] 중 |
| Peloton Guide | TV에 거는 카메라 | — | Movement Tracker(동작에 따라 로고가 채워짐)와 반복 추적. 자세 교정 여부는 확인 못 함 | [37] 중·하 |
| Hinge Health TrueMotion | "폰 배치에 제약이 거의 없는" 3D, 랜드마크 100개 이상(골반·엄지발가락 포함) | 불필요하다고 주장 | 화면 운동 안내, ROM·정렬 | [38] 중(회사 주장, 독립 검증 없음) |
| Sword Health | Phoenix는 벨크로로 붙이는 관성 트래커 + 태블릿 | 해당 없음 | 실시간 | [39] 하 |
| Exer · Sency | 배치 가이드 **미확인** | — | — | — |

## 5. 깊이 센서
- ARCore Depth[40](상): depth-from-motion 방식이라 **폰을 움직이기 시작한 뒤에야** 유효한 깊이가 나온다. ToF가 있으면 폰이 정지해 있거나 사람이 움직이는 장면에서도 깊이를 낸다. 범위는 0~65 m이고 0.5~5 m에서 가장 정확하다. 흰 벽처럼 특징이 없는 면에서는 부정확하다.
- 전면 카메라로 ARCore 세션을 열면 추적이 멈추고 평면 인식과 앵커가 꺼진다[41]. 따라서 전면 카메라로는 Depth를 쓸 수 없다[추론, 상].
- 보급률[42](상, 2026-09 조회): 'Supports Depth API' 표기는 약 1,150건, 'ToF 하드웨어 깊이 센서' 표기는 **11건**이다. LG V60, Galaxy S10 5G·Note10+·S20+·S20 Ultra·A80, AQUOS R5G 등 모두 2019~20년 모델이다. 삼성은 S21부터 ToF를 뺐다는 보도가 있다(쓸 곳이 적다는 이유)[43](하).
- 사람 몸을 잴 때의 Depth API 정확도는 **미확인**이다. 보급률, 고정된 폰, 전면 카메라 불가라는 세 조건에 모두 걸려 서비스 경로로는 맞지 않는다.

## 6. 스쿼트 국면의 시간과 샘플링
- 맨몸 스쿼트(자유 속도, ACL 재건 6개월 환자 35명, 모션캡처 120 Hz): 하강 **1.00 ± 0.25 s**, 상승 **0.96 ± 0.19 s**[44](상, 환자군이다. 건강인 수치는 미확인).
- 6RM 마지막 반복(훈련자 15명): sticking region이 **0.21 ± 0.10 s** 이어지고, 바가 최저점에서 0.10 ± 0.04 m 올라온 지점에서 시작한다. 속도가 가장 낮은 시점은 상승 시작 약 0.54 s 뒤이고, 바 최고 속도는 0.723 m/s다[45](상).
- **hip rise가 나타나는 국면**:
  - 상승은 초반의 무릎 우세에서 고관절 우세로 넘어간다. 모든 부하 조건에서 '대퇴가 올라가며 몸통이 숙여지는' 결합이 나타나고, 최대를 넘는 부하에서 이 구간이 길어진다[47](상).
  - 상승 초반에는 고관절 굴곡과 몸통 전경이 크고, sticking 구간에서 고관절 모멘트 기여가 51.5~54.4 %로 가장 높다[46](상).
  - 초보자에게 흔한 오류는 엉덩이가 어깨보다 빨리 올라와 체간 굴곡이 커지는 것이다[30](상).
  - 따라서 관찰 창은 **상승 초반(바닥을 찍은 뒤 약 0.5 s)**이다.
- **외반이 나타나는 국면**: Myer는 "어느 국면에서든"이라고 했다[30]. 한 다리 스쿼트에서는 하강 구간 59 % 지점에서 무릎이 가장 안쪽이었다[25]. **양발 스쿼트에서 하강과 상승을 비교한 문헌은 찾지 못했다.** REHAB24-6의 3D GT(30 fps)로 직접 잴 수 있다.
- **샘플링 문헌**: 스쿼트 연구는 120 Hz로 받아 12 Hz 필터[44], 500 Hz로 받아 10 Hz 필터[46]를 썼다. 착지·방향 전환처럼 빠른 과제에서는 30 fps와 120 fps의 FPPA 차이가 2.2~3.5°였다[48](상).
- **[계산] 300 ms 간격의 손실**:
  - 나이퀴스트 1.67 Hz, 국면당 약 3샘플이다.
  - 최저점 무릎각을 놓치는 정도(주기 2 s 사인파, 진폭 45° 가정): 3.3 Hz에서 평균 1.6°·최악 4.9°, 10 Hz에서 0.2°·0.6°, 30 Hz에서 최악 약 0.06°다.
  - 0.2 s짜리 사건(sticking, 순간적인 무릎 모임)은 3.3 Hz에서 잡힐 확률이 약 67 %이고 잡혀도 1샘플이다. 10 Hz면 2샘플, 30 Hz면 6샘플이다.

## 7. 온디바이스 연속 추론의 발열·배터리
- MediaPipe Pose를 15~30 fps로 계속 돌린 배터리·발열 실측 문헌은 **찾지 못했다**.
- 대신 참고할 근거:
  - 연산량: Pixel 3의 Full 모델은 CPU 약 18 FPS라 30 fps가 불가능하고, GPU는 약 40 FPS다[11]. Pixel 4 GPU에서 Full이 9 ms라 30 Hz면 GPU 시간의 약 27 %, 10 Hz면 약 9 %를 쓴다[계산, 12].
  - 연속 추론 스로틀: TFLite로 여러 DNN(MobileNet, EfficientDet, YOLOv3 등)을 Dimensity 9000 등에서 돌리자 **2.5분 만에** 스로틀이 걸려 CPU가 3 GHz에서 1 GHz로 내려갔고, 성능은 최대 4.3배 떨어졌다. GPU 스로틀이 더 심했다[49](중, 포즈 모델이 아님).
  - 카메라·화면·네트워크를 1시간 쓰는 영상통화에서 배터리 온도가 46 °C(Vivo), 52 °C(Motorola)까지 올랐다[50](중, 비슷한 부하로 보는 값).
  - MediaPipe 포즈는 사람이 없어도 CPU를 많이 쓴다. 사람이 있을 때만 돌리면 전력을 최대 30 % 아꼈다(단일 보드 컴퓨터)[51](중).
- 제안: `PowerManager.addThermalStatusListener`[55]와 배터리 잔량을 기록하며 3.3/10/15/30 Hz를 20~30분씩, 기종 2~3개로 실측한다.

## 8. REHAB24-6 (Černek·Sedmidubsky·Budikova)
- 논문은 SISAP 2024(최우수 논문상)와 Information Systems 2025 확장판이다[53]. **둘 다 폐쇄 접근이라 본문을 확인하지 못했다.** 아래는 Zenodo[52]와 로컬 파일 기준이다.
- **녹화 환경**: 8.2×7 m 실험실에서 센서 18개(광학 모션캡처 16대 + RGB 2대, 30 fps)를 동기화했다. **Camera17은 가로(화각 넓음), Camera18은 세로(화각 좁음)이고 둘 다 방 구석에 있다.** 피험자는 10명(남 6·여 4, 25~50세)이고, 마커 41개로 관절 26개를 얻었다. 녹화 65개, 184,825프레임, 반복 1,072회이며 조명은 저녁 자연광과 인공조명이 섞여 있다.
- **촬영 방향**: 운동마다 두 방향으로 했다. 가로 카메라를 바라보면 그 카메라에는 정면, 다른 카메라에는 측면으로 찍힌다. 두 카메라 사이 벽을 바라보면 둘 다 반측면(half-profile)이다. `cam17_orientation`은 front/half-profile/profile이고, 18번은 front와 profile이 반대로 매핑된다[52].
- **로컬 확인**: Camera17은 1920×1080, Camera18은 1080×1920(`-transposed`), 둘 다 30 fps다. `Segmentation.csv`에서 스쿼트는 195회(9명)로 오류 61회·정상 134회이고, cam17 방향은 front 98회·half-profile 97회다. 위 매핑을 따르면 **cam18에 측면 스쿼트가 98회** 있다.
- **잘못된 수행**: 치료사가 정상 5회 이상과 잘못된 5회를 지시했고, "피험자마다 다른 실수를 제안했다"[52]. **스쿼트 오류 유형 목록은 공개 자료에 없다**(라벨은 correctness 0/1뿐). Tang 2025[54]의 스쿼트 피처(무릎·고관절·몸통각, 발 대칭)는 그 논문이 고른 것이지 데이터셋의 정의가 아니다(그 논문은 모션캡처를 IMU로 잘못 적기도 했다).
- **카메라 높이·각도**: **미확인**이다. 관절 26개의 3D와 2D 투영 GT가 있으니 PnP로 카메라 위치·높이·피치를 역산할 수 있다(데이터 에이전트 몫).

## 9. 미확인 항목과 다음 실측 제안
- REHAB24-6의 스쿼트 오류 유형(논문 본문 필요)과 카메라 높이·각도 → PnP로 역산한다. cam18 측면 98회로 측면 뷰 MediaPipe의 무릎·몸통·뒤꿈치 오차를 3D GT에 대어 잰다.
- MediaPipe heel·foot_index의 정량 오차, 사선 45° 뷰 수치, 초광각 가장자리 왜곡이 관절각에 주는 영향 → 폰 라벨 세트에서 뷰를 나눠 측정한다.
- 양발 스쿼트에서 외반이 하강과 상승 중 언제 나타나는가 → REHAB24-6 3D GT로 오류 반복을 30 fps로 분석한다. 같은 GT를 3.3/10 Hz로 솎아내 §6의 손실을 직접 잰다.
- 뒤꿈치 들림의 영상 검출 → 옆면 폰으로 지정 오류 세트를 찍는다(원칙 #2: 확정 전에는 beta).
- MediaPipe 연속 구동의 배터리·발열 → §7의 프로토콜로 잰다.
- 상용 앱이 스쿼트에 측면 촬영을 요구하는지, Sency·Exer의 배치 가이드, Peloton의 자세 교정 여부.
- 전면 카메라에서 생기는 도메인 차이(모델은 후면 이미지로 학습)의 크기 → 같은 세트를 전면·후면으로 동시에 찍어 비교한다.
- 삼성 기기의 DISTORTION_CORRECTION 미지원(포럼 보고) → 기기별 `availableModes`를 로그로 남긴다.

## 출처
[1] AOSP 카메라 메타데이터 정의(distortionCorrection.mode, control.zoomRatio, aeTargetFpsRange, lens.info.minimumFocusDistance) — https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/metadata_definitions.xml
[2] Android Multi-camera API — https://developer.android.com/media/camera/camera2/multi-camera
[3] CameraX ImageAnalysis.java — https://github.com/androidx/androidx/blob/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/ImageAnalysis.java
[4] CameraX PreviewTransformation.java — https://github.com/androidx/androidx/blob/androidx-main/camera/camera-view/src/main/java/androidx/camera/view/PreviewTransformation.java
[5] CameraX 릴리스 노트 — https://developer.android.com/jetpack/androidx/releases/camera
[6] CameraX 개발자 그룹: 0.5x 줌(2025-01) https://groups.google.com/a/android.com/g/camerax-developers/c/N4YtXK-6-CU · 보조 카메라(2020-11) https://groups.google.com/a/android.com/g/camerax-developers/c/Z6kCmARMKsQ
[7] CameraX 개발자 그룹: 기본 FPS(2023-05) — https://groups.google.com/a/android.com/g/camerax-developers/c/n_6b8pDBgHA
[8] Android Developers Blog, Low Light Boost(2025-12) — https://android-developers.googleblog.com/2025/12/brighten-your-real-time-camera-feeds.html
[9] GSMArena: S24 https://www.gsmarena.com/samsung_galaxy_s24-12773.php · A55 https://www.gsmarena.com/samsung_galaxy_a55-12824.php · A35 https://www.gsmarena.com/samsung_galaxy_a35-12705.php · Pixel 8 https://www.gsmarena.com/google_pixel_8-12546.php
[10] Samsung Developer Forum, 초광각 왜곡 보정 — https://forum.developer.samsung.com/t/camera2-ultra-wide-lens-image-distortion-correction/21597
[11] Model Card BlazePose GHUM 3D — https://storage.googleapis.com/mediapipe-assets/Model%20Card%20BlazePose%20GHUM%203D.pdf
[12] Grishchenko et al. 2022, BlazePose GHUM Holistic — https://arxiv.org/abs/2206.11678
[13] MediaPipe Pose Landmarker 가이드 — https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker
[14] Physio2.2M, Sci Rep 2025 — https://pmc.ncbi.nlm.nih.gov/articles/PMC12589393/
[15] Dill et al. 2023, Curr Dir Biomed Eng — https://doi.org/10.1515/cdbme-2023-1141
[16] Dill et al. 2024, Sensors(스테레오 MediaPipe) — https://pmc.ncbi.nlm.nih.gov/articles/PMC11644880/
[17] Estimation of the Knee Joint with Single-Camera Smartphone, Sensors 2026 — https://pmc.ncbi.nlm.nih.gov/articles/PMC13075125/
[18] Gait analysis: manual vs 2D pose vs Vicon, Front Rehabil Sci 2023 — https://pmc.ncbi.nlm.nih.gov/articles/PMC10511642/
[19] Examination of 2D frontal and sagittal markerless motion capture, PLoS One 2023 — https://pmc.ncbi.nlm.nih.gov/articles/PMC10635560/
[20] 2D 정면 운동학 신뢰도·타당도 메타분석, JOSPT 2018(PMID 29895235) — https://www.jospt.org/doi/10.2519/jospt.2018.8006
[21] Schurr et al. 2017, IJSPT — https://pubmed.ncbi.nlm.nih.gov/28515970/
[22] Willson & Davis 2008, JOSPT — https://pubmed.ncbi.nlm.nih.gov/18827327/
[23] AI 포즈 추정으로 잰 한 다리 스쿼트, J Biomech 2022 — https://doi.org/10.1016/j.jbiomech.2022.111333
[24] MediaPipe 무릎 외반, 한 발 착지, Heliyon 2024 — https://pmc.ncbi.nlm.nih.gov/articles/PMC11399566/
[25] Does Medial Knee Position Reflect Dynamic Knee Valgus?, IJSPT — https://pmc.ncbi.nlm.nih.gov/articles/PMC13431038/
[26] OpenPose 양발 스쿼트 신뢰도·타당도, Gait Posture 2020(PMID 32485426) — https://doi.org/10.1016/j.gaitpost.2020.05.027
[27] 스쿼트·데드리프트 요추·골반 움직임의 시각 평가, Phys Ther Sport 2021 — https://doi.org/10.1016/j.ptsp.2021.05.011
[28] Ogata et al. 2019, CVPRW — https://openaccess.thecvf.com/content_CVPRW_2019/papers/CVSports/Ogata_Temporal_Distance_Matrices_for_Squat_Classification_CVPRW_2019_paper.pdf
[29] Fitness-AQA(ECCV 2022) — https://arxiv.org/abs/2202.14019
[30] Myer et al. 2014, Back Squat Assessment, Strength Cond J — https://pmc.ncbi.nlm.nih.gov/articles/PMC4262933/
[31] Turner et al. 2026, Markerless Pose Estimation for Resistance Training Technique Assessment — https://arxiv.org/abs/2608.24384
[32] Kaia Motion Coach, JMIR 2021 — https://pmc.ncbi.nlm.nih.gov/articles/PMC8317029/
[33] Kemtai 리뷰(Laptop Mag) — https://www.laptopmag.com/reviews/kemtai-adaptive-home-exercise-platform
[34] Zenia 리뷰(MyHealthyApple, 물리치료사) — https://www.myhealthyapple.com/a-physical-therapists-review-of-zenias-yoga-app/
[35] Tempo Move(SlashGear) — https://www.slashgear.com/tempo-move-is-a-400-home-gym-that-leans-on-clever-iphone-tech-02697919/
[36] Onyx(The Gadgeteer 2020) — https://the-gadgeteer.com/2020/08/17/onyx-helps-you-workout-without-going-to-a-gym-or-expensive-equipment/
[37] Peloton Guide 리뷰(Tom's Guide) — https://www.tomsguide.com/reviews/peloton-guide-review
[38] Hinge Health TrueMotion — https://www.hingehealth.com/resources/articles/truemotion-turning-a-phone-camera-into-a-3d-motion-lab/
[39] Sword Health 임상시험 프로토콜(NCT03648060) — https://cdn.clinicaltrials.gov/large-docs/60/NCT03648060/Prot_SAP_001.pdf
[40] ARCore Depth — https://developers.google.com/ar/develop/depth
[41] ARCore CameraConfig.FacingDirection — https://developers.google.com/ar/reference/java/com/google/ar/core/CameraConfig.FacingDirection
[42] ARCore 지원 기기 — https://developers.google.com/ar/devices
[43] TheElec, Galaxy S21 won't have ToF — https://www.thelec.net/news/articleView.html?idxno=1384
[44] Padron et al. 2025, ACL 재건 후 스쿼트 국면, Appl Sci — https://pmc.ncbi.nlm.nih.gov/articles/PMC12539646/
[45] The existence of a sticking region in free weight squats, J Hum Kinet 2014 — https://pmc.ncbi.nlm.nih.gov/articles/PMC4234771/
[46] New Insights About the Sticking Region in Back Squats, Front Sports Act Living 2021 — https://pmc.ncbi.nlm.nih.gov/articles/PMC8217455/
[47] Modified vector coding, max/sub-max back squat, J Biomech 2020 — https://doi.org/10.1016/j.jbiomech.2020.109830
[48] 2D 카메라 속도(30 vs 120 fps), J Clin Med 2025 — https://pmc.ncbi.nlm.nih.gov/articles/PMC11901006/
[49] ADMS, 모바일 다중 DNN 열 스로틀(arXiv 2503.21109) — https://arxiv.org/abs/2503.21109
[50] 영상통화 배터리 방전·온도, Sci Rep 2023 — https://pmc.ncbi.nlm.nih.gov/articles/PMC10359271/
[51] MediaPipe 포즈 CPU 부하를 열 센서로 줄이기, Sensors 2023 — https://pmc.ncbi.nlm.nih.gov/articles/PMC10708851/
[52] REHAB24-6 Zenodo — https://zenodo.org/records/13305826 · Segmentation.txt https://zenodo.org/records/13305826/files/Segmentation.txt
[53] Černek et al., SISAP 2024 https://doi.org/10.1007/978-3-031-75823-2_2 · Information Systems 2025 https://doi.org/10.1016/j.is.2025.102579
[54] Tang et al. 2025(LLM 재활 평가, REHAB24-6 사용) — https://arxiv.org/abs/2505.18412
[55] AOSP PowerManager(열 상태 리스너) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/PowerManager.java

# B3 — 문헌·기술 조사: 덤벨 컬의 가동 범위·반동·팔꿈치 벌어짐·카메라 뷰 (2026-09-25)

범위: 웹 문헌·연맹 규정·공개 코드만 봤다(데이터 실측은 B1·B2 몫). **확신도**: 상 = 1차 출처 본문에서 직접 확인, 중 = 초록·2차 출처·검색 요약만 확인, 하 = 취미 코드·블로그, **미확인** = 찾지 못함. 대괄호 번호는 문서 끝 출처. 각도는 특별히 적지 않으면 **0° = 팔꿈치 완전 신전** 인 굴곡각이다(MediaPipe 내각 = 180° − 굴곡각).

## 0. TREX 에 주는 결론
1. **"얼마나 올려야 1회"에 생리학적 정답은 없다.** 비대·근력 연구는 정점이 아니라 **바닥(신전) 쪽** 부분 ROM(0–50°[2], 0–68°[3], 0–70°[4])이 전체 ROM 과 같거나 낫다고 보고한다. 그래서 1회 정의는 운동 효과가 아니라 **동작 판별과 일관성**의 문제다 — 개인 시작 자세를 기준으로 하는 지금 설계(§62a)와 맞는다.
2. **연구·규정이 쓰는 "완전한 1회"는 신전 ≈0° ↔ 굴곡 130–140°** 다[1][2][3][4][5]. 그러나 서서 하는 실제 컬의 실측 ROM 은 **117–123°**[8]이고, "완전 신전에서 시작하라"고 해도 실제 시작은 **약 20° 굴곡**이다[9]. 절대각 임계(예: 내각 >160°/<30°[15])는 정상 반복을 놓친다.
3. **비율 개인화의 선례가 있다.** 부분 반복 = 개인 전체 ROM 의 약 50%[6], 국면 = 개인 ROM 의 33/67%[9], 임계 = 개인 범위의 30/70%[18]. 실측 %ROM 은 종목별로 달라(덤벨 컬 63%, CI 46–88%[7]) 고정 비율은 정의가 못 된다 — 비율은 **개인 기준선**에 걸어야 한다.
4. **반동의 관측 가능한 신호는 어깨 굴곡(상완–몸통 각)이다.** 정상 컬 약 10°, 피로 시 약 20°[29], 의도적 "팔 굴곡" 변형 30°[28], 검출 임계 35°[17]·40°[16]. **몸통 신전 크기를 잰 연구는 없다**(모두 육안 감시[8] 또는 미측정[27]) → 띠는 실측으로 정한다.
5. **팔꿈치 벌어짐은 문헌 수치가 없다.** 교본 지시("elbows at the sides"[13][14])와 오류 클래스 존재[31][32]뿐이고, 정면 2D 비율(팔꿈치 간격/어깨 간격)의 타당도 연구는 미확인 → beta 로 시작하고 모집단 오탐률을 먼저 잰다.
6. **뷰: 팔꿈치 굴곡각·어깨 굴곡은 시상면(옆)이 맞고 정면은 크게 틀린다.** 정면 2.5 m 에서 팔꿈치 굴곡 RMSE 14–51°[37], 시상면에서는 3–11°[36][38]. 벌어짐만 정면·사선에서 보인다. 세 오류를 한 뷰로 잡을 근거는 없다 — 사선이 타협안이지만 컬에 대한 사선 수치는 **미확인**.
7. **횟수와 자세를 분리하는 원칙(§62 원칙 7)은 문헌과도 맞는다.** Pose Trainer·Exercise-Correction 모두 ROM 판정과 폼 오류를 별도 규칙으로 둔다[16][17]. 반동·벌어짐으로 카운트를 막으면 오탐 하나가 1회를 지운다.
8. **교대 컬 단위는 관례가 갈린다**(양팔 = 1회[42][43] vs 팔당 표기[44][45]) → 앱이 단위를 화면에 명시해야 한다.

## 1. 가동 범위 정의
| 연구 | 종목·자세 | 각도 정의·범위 | ROM 통제 | 확신 |
|---|---|---|---|---|
| Pinto 2012 JSCR[1] | 양팔 바벨 프리처, 미훈련 남 | "0 = full elbow extension"; FULL 0–130°, PART 50–100°(고찰에는 0–140 표기 혼재) | 금속 바 2개로 바 이동 제한 | 상 |
| Sato 2021[2] | 덤벨 프리처, 어깨 45° 굴곡, 미훈련 32 | EXT 0–50°, FLE 80–130°(0° 정의 문장은 못 봄) | 메트로놈 2 s/2 s | 상(범위)·중(정의) |
| Pedrosa 2023[3] | 좌식 덤벨 프리처, 어깨 45°, 미훈련 여 19 | "0° = extended elbow", 135° = 전완 지면 수직; INITIAL 0–68°, FINAL 68–135°, 1RM 0–135° | 매 세션 각도계 + 탄성 코드 스톱 | 상 |
| Havers 2025 프리프린트[4] | 프리처 머신, 훈련자 13 | pROM 0–70°, fROM = "subject's maximal possible elbow flexion (approximately 0° to 140°)" | 웨이트 스택 고정 마커 | 상 |
| Retos 2025[5] | 스콧 벤치, 어깨 45°, 활동적 남 | fROM ≈0–135°, final pROM ≈50–135° | 밴드 + 금속 구조물 기준 | 상 |
| Wolf 2025 PeerJ[6] | 8종목(덤벨 컬 포함), 훈련자 25 | 각도 없음. 부분 = "approximately 50% of full ROM, relative to their own individualized full ROM" 을 하단부터 | 연구진 구두 교정 | 상 |
| Marcolin 2018[8] | 서서 바벨/EZ/덤벨, 12명, 65% 1RM | **전기각도계 실측** ROM: 바벨 117.3±10.9°, EZ 119.9±13.7°, 덤벨 123.1±12.3° | 3 s/회 | 상 |
| Oliveira 2009[9] | 서서·인클라인·프리처 덤벨 | "instructed to start from full elbow extension" 이지만 실제 시작 "around 20°"; 국면을 개인 ROM 의 0–33/34–67/68–100% 로 나눔 | 손목 가속도계 | 상 |

- 결과 요지(비대): 전체 vs 중간 부분(50–100°) 비대 동등, 1RM 은 전체가 큼(+25.7 vs +16.0%)[1]; 신전 쪽 부분이 굴곡 쪽보다 두께 +8.9 vs +3.4%[2]; 원위부 CSA 는 INITIAL > FINAL[3]; 훈련자에서 0–70° 는 원위부 소폭 우세, 근력은 전체 우세[4]; 50% 부분 = 전체[6].
- **AI 실측 %ROM**(Wolf 영상 303개, 5개 포즈 모델, 카메라 표준화·해부학 검증 없음)[7]: 부분/전체 평균 ≈56%, 덤벨 컬 63.4% [45.8, 87.6], 인클라인 프레스 32%, 오버헤드 익스텐션 82% → "lengthened partials are not characterized by a fixed proportion of full ROM".
- **스트릭트 컬 규정**(1회 인정 기준): RPS[10] — 상등·둔부를 수직판에 밀착, 뒤꿈치선 12", 시작 "arms extended fully as possible"·무릎 잠금, 종료 "bar near chin or throat … elbow fully closed as possible", 신호 'Curl'/'Down', 실패 = 종료 전 하강·등/둔부 이탈·무릎 굽힘·발 이동·비동시 락아웃. ISCA[11] — 머리·어깨·둔부 벽 밀착, 뒤꿈치 12" 이내, "The legs and hips may not be used in any way for momentum … may not lean back", 실격에 "bending the back to assist … starting the upward motion". **팔꿈치 전방 이동은 두 규정 모두 금지하지 않는다**(팔꿈치 조항 없음). USPA 2024 룰북(68쪽)은 텍스트에 'curl' 0건 — 스트릭트 컬 규정이 없다[12](상, 텍스트 검색 기준).
- **교본**: NSCA 기초 매뉴얼 EZ바 컬[13] — 시작 "elbows completely extended", 정점 "pull the bar up to the shoulders until elbows are completely flexed", 코칭 "Keep elbows positioned at the sides", "Avoid using momentum", 코어로 "prevent rocking back and forth". ACE[14] — "keeping them next to the middle of the body", "Keep chest still". NSCA 기술 매뉴얼·ACSM 본문은 **미확인**.
- **앱·코드의 반복 기준**: 튜토리얼 계열[15] 내각 >160° = down, <30° = up 이면 +1(고정, 개인화 없음). Exercise-Correction[16] 정점 내각 >60° = "weak peak contraction". Pose Trainer[17] 최소 내각 >70° = 미완성. ai-personal-trainer[18] 개인 범위의 30%/70% 를 임계로, 40° 미만 스윙 무시. Springer 2024[19] 7종목 규칙 기반이나 수치 미공개(중). 웨어러블[31] 전완 IMU+EMG 로 표준·elbow-fling·partial-up·partial-down·hip-swing 5분류, 테스트 83.3%(정의 문장·임계 없음).

## 2. 개인화 비율
- **정상 팔꿈치 ROM**(수동, 각도계, CDC/Soucie 2011[20]): 20–44세 남 굴곡 144.6°(143.6–145.6)·여 150.0°(149.1–150.9); 45–69세 남 143.5°·여 148.3°; 신전 남 0.8°·여 4.7°. Zwerus 2019 리뷰[21]: 능동 굴곡(우세측) 146°, 신전 −2°; "Male subjects had smaller ROM compared to females"; 우세/비우세 차 <1°; "influenced by age, sex and BMI"; 수동이 능동보다 3–5° 큼(요약, 중); 반대쪽 팔을 기준으로 써도 된다. AAOS 150°[23](2차, 중). 기능적 아크 30–130°(Morrey 1981[24], 초록 요약, 중).
- **체형이 끝을 막는다**[22]: "limited by soft tissue approximation between the structures of the anterior arm and the forearm, particularly during active flexion … contact between contracting flexors of the arm and forearm stops the motion"; "greater when the joint is moved passively". 굵기별 각도 수치는 **미확인** → 굴곡 끝은 개인마다 다르고 능동 측정이 수동보다 작다. 절대 정점각(예 140°)을 요구하면 근육량이 큰 사용자가 불리하다.
- **비율 선례**: 개인 전체 ROM 의 50%[6]; 개인 ROM 의 %로 국면 분할[9]; 개인 최대 굴곡을 fROM 으로[4]; 30/70% 임계[18]; 재활 엑서게임은 능동 ROM 의 60–80% 에 표적을 둔다[26](요약, 중). 반대: %ROM 은 종목·개인 편차가 커서 "고정 비율" 자체는 정의가 못 된다[7] — 개인 기준선 위의 비율만 의미가 있다.

## 3. 반동(cheat curl) 운동학
- **정의**(Schoenfeld 계열 RCT[27], n=30, 8주): STRICT = "maintain a stationary body position and to avoid swinging the torso (i.e., hyperextending the hips and spine), shrugging the shoulders, hyperextending the neck, extending the knees, or rising on the toes"; CHEAT = 외부 모멘텀 권장, 볼륨로드 약 2배, 비대 차이 없음. **운동학 미측정**("difficult to standardize form in CHEAT"). Marcolin[8]도 몸통·무릎을 "visually monitored" 만 했다. → **몸통 신전·엉덩이 신전의 크기(°)를 잰 컬 연구: 미확인.**
- **어깨 굴곡·거상 수치**(Vicon, 12남, 5 kg, 30 bpm, 피로 Borg 7)[29]: 어깨 굴곡 표준 ≈10° → 피로 ≈20°; 어깨 거상 20° → 30°; 상부승모근 기여 +62.5%(RMS +127%); 팔꿈치 F/E 는 "50 to 150 degrees" 로 불변(각도 관례 미기재). 권고: "focus on the shoulder muscle activities and joint motions". 어깨 패치[30]는 13명 피로까지 거상 변화로 검출(각도 수치 미추출).
- **의도적 팔 굴곡**[28]: 바벨 컬에 어깨 굴곡 ≈30° 를 더하면 이두 nRMS +17.7~20.3%(상승기), 전삼각근도 변화; 모든 변형에서 "the arms were close to the trunk". 즉 30° 어깨 굴곡은 "오류"라기보다 변형이며, 스트릭트 컬 규정도 팔꿈치 이동을 허용한다(§1).
- **검출 코드**: Pose Trainer[17] 측면 촬영, 상완–몸통 각 **범위** >35° = "too much shoulder rotation"(정상 예 21°, 오류 예 35°), 16영상에서 정상 100%·오류 80% 검출. Exercise-Correction[16] 팔꿈치–어깨–어깨 지면투영 각 >40° = "loose upper arm", 뒤로 젖힘은 ML. → **팔꿈치 전방 이동 = 어깨 굴곡각(상완 벡터 vs 몸통 벡터)** 로 정의하는 것이 관례이며, 사용자가 제안한 "팔꿈치 좌표 이동" 은 어깨 굴곡 + 몸통 이동의 합이라 두 성분을 나눠 봐야 한다.

## 4. 팔꿈치 벌어짐(elbow flare)
- 근거는 교본 지시뿐: "Keep elbows positioned at the sides"[13], "next to the middle of the body"[14]; EMG 연구도 팔을 몸통에 붙인 조건만 잰다[28]. [27]은 "elbows to flare" 를 **푸시다운**의 치팅으로만 정의했다.
- 간접 근거[33]: 팔을 90° 외전하면 "elbow flexion and extension requires less force from the humeral-originating muscles (biceps, brachialis, and triceps)"; 내반 변화는 0.9–1.6° 로 작다 → 벌어질수록 이두 부하가 준다는 방향은 맞지만 컬 EMG 로 잰 것은 아니다(중).
- 오류 클래스로 존재: elbow-fling(전완 IMU)[31]; BlazePose + 결정트리 10종 이상("misplaced elbows and wrists", 몸통 각 이상), 민감도 73.7–97.4%, 정의·뷰 미공개[32].
- **2D 지표(팔꿈치 간격 ÷ 어깨 간격)의 학술 연구: 미확인.** 취미 저장소의 비율 스펙(어깨너비 대비 팔꿈치 1.1–1.4 → 0.85–0.9)만 검색에 잡혔고 페이지는 404, 블로그[34]도 수치가 없다. 정면 뷰의 랜드마크 폭은 몸 회전(view_cos)에 같이 줄어드니 어깨너비로 나눈 뒤에도 뷰 게이트가 필요하다(우리 해석).

## 5. 카메라 뷰
| 근거 | 조건 | 수치 | 확신 |
|---|---|---|---|
| JMIR mHealth 2026[35] | 폰, 정면 0°/사선 45°/측면 90°, 90/180/200/360 cm, 스쿼트·푸시업 | 최적 = 180–200 cm 의 **사선 또는 정면**; 팔 종목 없음 | 중(초록) |
| Yahya 2018[36] | OpenPose vs Kinect, 컵→입, 5명 | RMSE 시상 3.06°, 관상 0.95°; 시상면 ±10° 오차는 "imprecise detection of the wrist" | 상 |
| 상지 HPE 타당도 2024[37] | **정면** 2.5 m 폰, MoveNet/PoseNet vs 각도계, 52명 | 팔꿈치 **굴곡** RMSE 13.7–51.0°("unsatisfactory for elbow flexion"), 신전 5.9–8.7° | 상 |
| 팔꿈치 ROM 검증 2026[38] | **시상면** 3 m·0.9 m 높이, Vicon 기준, 46명 | HSMR 굴곡 MAD 4.95°(CCC .92), RTMW 10.94°; 신전 5.5–6.3° | 상 |
| Sci Rep 2026 하이브리드[39] | MediaPipe Full, 운동면에 **수직** 2.5 m·높이 1.0 m, 모션캡처 기준 | 팔꿈치 ROM 12–14° 과대추정(≈10%); 좌우 ICC 비대칭은 카메라 배치 탓 | 상 |
| EDS 각도계 비교[40] | MediaPipe 등 5개 vs 각도계 | 팔꿈치 rho .653(MediaPipe), .722(Detectron) | 상 |
| Pose Trainer[17] / 20명 데이터셋[41] | 컬은 **측면** 촬영 전제 / MediaPipe > OpenPose | 뷰 수치 없음 | 상/중 |
- 해석: 정면에서는 굴곡된 전완이 카메라를 향해 단축돼 굴곡각이 무너진다[37]; 어깨 굴곡(반동)도 시상면 양이다. 벌어짐은 정면·사선에서만 보인다. 컬의 **사선 뷰 수치는 미확인** — A5 §2 의 일반 결론(정면 3D 는 깊이 손해, 측면은 가려진 팔 오차 2배)이 그대로 적용된다. [7]은 팔꿈치 랜드마크가 "minimally affected by occlusion or camera perspective" 라 했지만 검증 없이 쓴 주장이다.

## 6. 교대 컬
- 양팔 한 쌍 = 1회: Trainwell "Repeat the movement with the other arm. That is one rep."[42]; 약한 팔 기준으로 세거나 두 배로 세는 대안 소개[43].
- 팔당 표기: Fitbod 는 "per arm/per leg" 라벨로 팔당 횟수(검색 요약, 중)[44]; Jefit 사용자도 팔당 세기[45]. 팔별 카운트를 다룬 논문: **미확인**. → 런지(§59, 쌍 = 1회)와 같은 결정을 컬에도 내리려면 화면에 단위를 적어야 한다.

## 출처
1. Pinto 2012 JSCR — https://paulogentil.com/pdf/Effect%20of%20Range%20of%20Motion%20on%20Muscle%20Strength%20and%20Thickness.pdf (https://journals.lww.com/nsca-jscr/fulltext/2012/08000/effect_of_range_of_motion_on_muscle_strength_and.17.aspx)
2. Sato 2021 Front Physiol — https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2021.734509/full
3. Pedrosa 2023 Sports — https://pmc.ncbi.nlm.nih.gov/articles/PMC9960616/
4. Havers 2025 SportRxiv 프리프린트 — https://sportrxiv.org/index.php/server/preprint/view/611 (PDF /download/611/1314)
5. Retos 2025 (final pROM vs fROM, within-subject) — https://dialnet.unirioja.es/descarga/articulo/9798870.pdf
6. Wolf 2025 PeerJ (lengthened partials, 훈련자) — https://pmc.ncbi.nlm.nih.gov/articles/PMC11829627/
7. Diamant 2026 arXiv 2510.20012 — https://arxiv.org/pdf/2510.20012
8. Marcolin 2018 PeerJ — https://pmc.ncbi.nlm.nih.gov/articles/PMC6047503/
9. Oliveira 2009 JSSM — https://www.jssm.org/jssm-08-24.xml-Fulltext
10. RPS Strict Curl Rules — https://www.revolutionpowerlifting.com/rulebook/?page_id=248
11. ISCA Strict Curl Rules — https://static1.squarespace.com/static/592db75b15d5db03e8ac7de7/t/5b4a46c4f950b741b89df582/1531594436768/ISCA+STRICT+CURL+RULES.pdf
12. USPA Technical Rules 2024 — https://uspa.net/wp-content/uploads/2024/03/USPA-Rulebook-March-13th-2024.pdf
13. NSCA Basics of Strength and Conditioning Manual — https://www.nsca.com/contentassets/48a12160221541acbdc048498d77192d/basics_of_strength_and_conditioning_manual.pdf
14. ACE Exercise Library: Bicep Curl — https://www.acefitness.org/resources/everyone/exercise-library/70/bicep-curl/
15. 튜토리얼 계열 카운터(예) — https://github.com/niteshctrl/biceps-curl-counter
16. Exercise-Correction bicep README — https://github.com/NgoQuocBao1010/Exercise-Correction/blob/main/core/bicep_model/README.md
17. Chen & Yang, Pose Trainer (arXiv 2006.11718) — https://arxiv.org/pdf/2006.11718
18. ai-personal-trainer — https://github.com/AYMANE-SNOUSSI/ai-personal-trainer
19. Real-time weight training counting and correction using MediaPipe (2024) — https://link.springer.com/article/10.1007/s43674-024-00070-w
20. CDC Joint ROM Study / Soucie 2011 Haemophilia — https://archive.cdc.gov/www_cdc_gov/ncbddd/jointrom/index.html , https://pubmed.ncbi.nlm.nih.gov/21070485/
21. Zwerus 2019 Shoulder Elbow — https://journals.sagepub.com/doi/10.1177/1758573217728711 (초록은 Europe PMC REST 로 확인)
22. Musculoskeletal Key, Measurement of ROM of the Elbow and Forearm — https://musculoskeletalkey.com/measurement-of-range-of-motion-of-the-elbow-and-forearm/
23. AAOS 정상치 표(2차) — https://goniometer.io/range-of-motion
24. Morrey 1981 JBJS — https://pubmed.ncbi.nlm.nih.gov/7240327/
25. (예비) Sardelli 2011 JBJS — https://pubmed.ncbi.nlm.nih.gov/21368079/
26. 상지 엑서게임 보정(요약만) — https://pmc.ncbi.nlm.nih.gov/articles/PMC12580367/
27. Do cheaters prosper? (2025) — https://pmc.ncbi.nlm.nih.gov/articles/PMC11970409/
28. Bilateral biceps curl straight vs EZ, arms flexion/no-flexion (2023) — https://pmc.ncbi.nlm.nih.gov/articles/PMC9944112/
29. Fatigue-induced compensatory movements in bicep curls (arXiv 2402.11421) — https://arxiv.org/html/2402.11421v2
30. Strain-sensor shoulder patch (arXiv 2501.14792) — https://arxiv.org/pdf/2501.14792
31. Liu 2026 IJRA, IBKA-BPNN biceps curl classification — https://ijra.iaescore.com/index.php/IJRA/article/download/21056/13257
32. Anomaly detection of bicep curl using pose estimation (AHFE) — https://openaccess.cms-conferences.org/publications/book/978-1-958651-81-0/article/978-1-958651-81-0_6
33. Arm abduction and elbow flexion kinematics (2025) — https://pmc.ncbi.nlm.nih.gov/articles/PMC11963008/
34. builtwithscience 컬 실수(수치 없음) — https://builtwithscience.com/fitness-tips/fix-bicep-curls-mistakes/
35. Oliosi 2026 JMIR mHealth e82412 — https://mhealth.jmir.org/2026/1/e82412
36. Yahya 2018 arXiv 1808.07017 — https://arxiv.org/pdf/1808.07017
37. Validity of monocular HPE for upper limb ROM (2024) — https://pmc.ncbi.nlm.nih.gov/articles/PMC11679233/
38. Validation of markerless HPE for elbow ROM (2026) — https://pmc.ncbi.nlm.nih.gov/articles/PMC13395372/
39. Hybrid AI vision model, single camera (Sci Rep 2026) — https://www.nature.com/articles/s41598-026-55431-x
40. Vision-based goniometry in EDS (2023) — https://pmc.ncbi.nlm.nih.gov/articles/PMC10712662/
41. Assessing Bicep Curl Exercises by Human Pose Application (2023) — https://link.springer.com/chapter/10.1007/978-3-031-27524-1_55
42. Trainwell, Alternating Dumbbell Curl — https://www.trainwell.net/exercises/alternating-dumbbell-curl
43. The Nest, How to Count Alternating Bicep Curls — https://woman.thenest.com/count-alternating-bicep-curls-2506.html
44. Fitbod Help, Sets/Reps fields — https://help.fitbod.me/hc/en-us/articles/29486697282711-Exercise-Details-Screen-Sets-Reps-and-Weight-Fields-Explained
45. Jefit Q&A(사용자 관행) — https://www.jefit.com/q&a/98799008/

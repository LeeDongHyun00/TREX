# 바닥 규칙 내보내기 (rules_floor_v0)

- 종목당 단일 뷰 고정, CV AUC ≥ 0.72, 수행자 ≥ 3 → **채택 17규칙 / 9종목**
- 전 규칙 status=beta: AIHub 에 바닥 높이 카메라가 없어 임계값은 미보정 — 세트 로그 재보정 전까지 참고용

| 종목 | 뷰 | 조건 | CV AUC | 채택 | 피처 |
|---|---|---|---|---|---|
| Y - Exercise | B | 경추 중립 또는 후인(retraction) 유지 | 0.759 | ✅ | `hip_dev_knee__min` |
| Y - Exercise | B | 엄지손가락 하늘방향 | 0.645 | — | `hand_shoulder_off__min` |
| Y - Exercise | B | 양 팔 높이 동일 | 0.627 | — | `hip_dev_knee__range` |
| 니푸쉬업 | B | 고개 젖힘/숙임 여부 | 0.843 | ✅ | `head_trunk_ang__mean` |
| 니푸쉬업 | B | 손의 위치 가슴 중앙 여부 | 0.788 | ✅ | `shoulder_arm_ang__mean` |
| 니푸쉬업 | B | 가슴의 충분한 이동 | 0.741 | ✅ | `elbow_width__std` |
| 니푸쉬업 | B | 척추의 중립 | 0.631 | — | `hip_dev_ankle__mean` |
| 니푸쉬업 | B | 이완시 팔꿈치 90도 | 0.597 | — | `shoulder_arm_ang__std` |
| 라잉 레그 레이즈 | E | 고개 숙임 여부 | 0.896 | ✅ | `head_trunk_ang__mean` |
| 라잉 레그 레이즈 | E | 허벅지와 종아리 각도 고정 | 0.765 | ✅ | `knee_ang__std` |
| 라잉 레그 레이즈 | E | 이완 시 다리 긴장유지 | 0.621 | — | `ankle_ground__min` |
| 라잉 레그 레이즈 | E | 허리 지면 고정 | 0.614 | — | `hip_dev_knee__std` |
| 바이시클 크런치 | E | 견갑골이 지면으로부터 충분히올라옴 | 0.761 | ✅ | `head_trunk_ang__min` |
| 바이시클 크런치 | E | 수축시 무릎 엉덩이 지남 | 0.617 | — | `hip_dev_knee__range` |
| 바이시클 크런치 | E | 허리 지면 고정 | 0.575 | — | `hip_ground__mean` |
| 시저크로스 | D | 시선 배꼽 고정 | 0.825 | ✅ | `head_trunk_ang__mean` |
| 시저크로스 | D | 다리와 지면 사이 적당한 거리 | 0.786 | ✅ | `shoulder_ground__max` |
| 시저크로스 | D | 무릎 너무 굽히지 않음 | 0.721 | ✅ | `knee_ang__std` |
| 시저크로스 | D | 허리 지면 고정 | 0.552 | — | `ankle_gap2d__std` |
| 시저크로스 | D | 수축 시 양무릎 교차 | 0.535 | — | `knee_dev__range` |
| 크런치 | C | 견갑골이 지면으로부터 충분히 올라옴 | 0.733 | ✅ | `head_ground__max` |
| 크런치 | C | 이완시 긴장 유지 | 0.594 | — | `ankle_ground__range` |
| 크런치 | C | 허리 지면 고정 | 0.572 | — | `elbow_width__max` |
| 크런치 | C | 어깨반동 없음 | 0.553 | — | `shoulder_arm_ang__std` |
| 푸시업 | C | 고개 젖힘/숙임 여부 | 0.890 | ✅ | `head_trunk_ang__mean` |
| 푸시업 | C | 가슴의 충분한 이동 | 0.781 | ✅ | `wrist_shoulder_d__min` |
| 푸시업 | C | 손의 위치 가슴 중앙 여부 | 0.765 | ✅ | `shoulder_dev__mean` |
| 푸시업 | C | 이완시 팔꿈치 90도 | 0.717 | — | `hand_shoulder_off__mean` |
| 푸시업 | C | 척추의 중립 | 0.635 | — | `shoulder_arm_ang__mean` |
| 플랭크 | B | 몸통과 엉덩이의 정렬 유지 | 0.794 | ✅ | `trunk_ankle_ang__mean` |
| 플랭크 | B | 상체의 지면으로부터 충분한 거리 유지 | 0.648 | — | `shoulder_arm_ang__mean` |
| 플랭크 | B | 팔꿈치가 어깨보다 안쪽에 위치하지 않음 | 0.539 | — | `shoulder_arm_ang__min` |
| 힙쓰러스트 | B | 고개 들지 않기 | 0.860 | ✅ | `head_trunk_ang__std` |
| 힙쓰러스트 | B | 수축시 무릎부터 어깨까지 일자 | 0.859 | ✅ | `hip_dev_ankle__max` |
| 힙쓰러스트 | B | 이완 시 긴장 유지 | 0.651 | — | `shoulder_dev__max` |
# UI/UX 기준 Top 10 · 실제 사례 Top 10 과 격차 페이지 디자인 평가·개정 (2026-11-05)

상태: **평가 완료 · 디자인 v2 반영.** 대상은 `docs/GAP_PAGE_PLAN.md` 의 18페이지 디자인(캔버스 v1, 2026-11-05 오전)과 저장소 프로토타입. 결과물은 캔버스 v2(아트보드 20장)와 저장소 사본 `docs/prototype/competitor-gap/`(원본 `canvas/`, 단독 화면 `screens/`, 갤러리 `index.html`), 화면 캡처 `docs/images/prototype/v2-*.png`(v1 은 `v1/`).

출처는 2026-11-05 에 1차 페이지(또는 Apple 의 HIG 데이터 엔드포인트·Google Android 개발자 문서)로 확인했다. 확인하지 못한 것은 *(미확인)* 으로 표시했다.

---

## 1. 디자인 기준 자료 Top 10

| # | 자료 | 무엇인가 | 이 평가에 쓴 구체 규칙 |
|---|---|---|---|
| A1 | **Nielsen 10 Usability Heuristics** (NN/g, 1994 · 2024 개정) — nngroup.com/articles/ten-usability-heuristics · **Response Times: 3 Limits** | 휴리스틱 평가의 표준 10항목 | 응답 0.1 s = 즉시, 1 s = 진행 표시, 10 s = 진행률+취소 |
| A2 | **Apple Human Interface Guidelines** — developer.apple.com/design/human-interface-guidelines (Accessibility·Typography·Tab bars·Sheets·Entering data·HealthKit) | iPhone 플랫폼 규칙 | 컨트롤 **44×44 pt**(최소 28), 글자 기본 17 pt·최소 11 pt, 200 % 확대, 대비 17 pt 이하 4.5:1 / 18 pt 이상·굵게 3:1, 탭 5개 이하 권장, 시트는 **그래버 + 스와이프 닫기 + Done 은 항상 Close 와 짝**, 중간 디텐트로 점진 공개, 건강 권한은 **필요한 순간에**, Activity 링 모양 모방 금지 |
| A3 | **Material Design 3** (Google) — developer.android.com(navigation-bar·accessibility), Flutter M3 TextTheme, material-components-android BottomSheet | Android·Compose 플랫폼 규칙 | 터치 대상 **48×48 dp, 간격 8 dp**, 대비 4.5:1(18 sp 미만)·3:1, 하단 내비 **3~5개**, 타입 스케일 body 16/14/12·label 14/**12**/11(11 미만 없음), **화면당 FAB 하나**, 채운 버튼은 흐름을 끝내는 행동(저장·확인)에만, 모달 시트는 스크림 탭·아래로 밀어 닫기·드래그 핸들 |
| A4 | **WCAG 2.2** (W3C, 2023-10-05) — w3.org/TR/WCAG22 | 접근성 성공 기준 | **1.4.3** 글자 4.5:1(큰 글자 3:1), **1.4.4** 200 % 확대, **1.4.11** UI 요소 3:1, **2.5.8** 대상 ≥ 24×24 px(AA)·**2.5.5** 44×44(AAA), **2.5.7** 드래그에 비드래그 대안, **3.3.4** 데이터 삭제는 되돌리기·확인·검사 중 하나, **3.3.7** 전에 입력한 값은 자동 채움, **2.2.1** 시간 제한은 20 s 전 경고·10회 연장 |
| A5 | **KWCAG 2.2** (KS X OT0003:2022, 한국형 웹 콘텐츠 접근성 지침) — a11ykr.github.io/kwcag22 (커뮤니티 미러) | 국내 인증에 쓰는 표준, 33 검사항목 | 5.4.3 명도 대비 4.5:1, 6.1.3 컨트롤 **대각선 6.0 mm 이상**, 6.2.1 시간 제한 사전 경고 20 s, 6.3.1 초당 3~50회 깜빡임 금지, 6.5.2 포인터 입력 취소, 6.5.4 동작 기반 작동 대체 수단 |
| A6 | **Laws of UX** (Jon Yablonski, 2024 2판) — lawsofux.com | 심리학 기반 법칙 30개 | **Doherty 400 ms**, Miller 7±2 묶음, Fitts(크고 가까운 대상), Hick(선택지 수), Jakob(익숙한 관례), **Peak-End**(마지막 화면), Von Restorff(주요 행동 하나만 다르게), Goal-Gradient(진행 바) |
| A7 | **Steven Hoober — How Do Users Really Hold Mobile Devices?** (UXmatters 2013) · **Design for Fingers, Touch, and People** (2017 정정) | 1,333건 관찰 | 한 손 49 %·받침 36 %·두 손 15 %, 2017 정정: **화면 중앙을 보고 누른다**, 탭 95 % 포함 크기 중앙 ≈ 7 mm·모서리 ≈ 12 mm → 주 내용 중앙·주요 행동 아래쪽, 위험한 행동은 그립을 바꿔야 하는 자리에 두지 않음 |
| A8 | **NN/g 패턴 글** — Progressive Disclosure(2006) · Confirmation Dialogs(2018) · Empty States 3 Guidelines(Kaplan 2021) · Input Steppers(2018) · Mobile Input Checklist(Budiu 2015) | 이번 화면(세트 입력·삭제·빈 기록)에 바로 닿는 규칙 | 점진 공개 **2단계 이하**·'더 보기' 라벨 명시, 확인 대화상자는 **되돌릴 수 없는 행동에만**·버튼 이름은 결과("운동 삭제"/"유지")·되돌리기 우선, 빈 상태는 **상태 → 설명 → 직접 CTA**, 스테퍼는 1단위·최소/최대 표시·직접 입력과 짝·대상 1 cm, 숫자 필드는 숫자 키패드·라벨 위·플레이스홀더를 라벨로 쓰지 않음·**이력에서 기본값** |
| A9 | **Don Norman, The Design of Everyday Things(2013 개정)** · **Shneiderman 8 Golden Rules** (6판 2016) | 기초 어휘 | 모든 탭 대상에 보이는 시그니파이어, 모든 행동에 피드백, 순서(세트→종목→세션)는 닫힘(요약)으로 끝, **모든 파괴적 행동은 되돌릴 수 있게**, 사용자가 시작 |
| A10 | **토스 UX Writing 원칙 · TDS** — toss.im/tossfeed/article/uxwriter-interview (2021) · developers-apps-in-toss.toss.im/design/components | 국내 앱 문구·컴포넌트 기준 | 군더더기 제거, 전문용어 0, **시트 제목 한 줄**, **단일 CTA 는 "~하기"**, 진행 버튼 "확인/다음", **두 버튼이면 부정은 "닫기"·"다음에"(취소 아님)**. "One Thing per Page" 는 토스 출처 *(미확인)* |
| +  | **deceptive.design** (Harry Brignull) · **Android Health Connect 디자인 지침** | 보상·권한 화면의 금지 목록 | confirmshaming·잔소리 알림·가짜 긴급·**연속 복구 구매**·화폐 혼동 금지; 건강 권한 버튼은 "Connect to Health Connect" 가 아니라 "설정하기/시작하기/공유할 데이터 선택", 맥락 안에서(체중 기록·운동 뒤) 요청, 설정에 진입점 |

## 2. 실제 사례 Top 10

| # | 앱 | 배울 패턴 (a 기록 · b 루틴 · c 목록 · d 추이 · e 보상 · f 설정) | 기록된 비판 |
|---|---|---|---|
| B1 | **Strong** (4.9★ 10.9만) | a 탭으로 세트 완료·자동 휴식 타이머·플레이트 계산기; d PR·추정 1RM·볼륨 차트; f **CSV 내보내기**, "계정 영구 무료·언제든 내보내기" | 무료 루틴 3개 상한에 대한 불만 |
| B2 | **Hevy** (4.9★ 10.2만) | a 세트 유형(워밍업·드롭·실패·슈퍼)·메모; b 루틴 라이브러리·남의 루틴 복사; c 영상 가이드·커스텀; d 종목별 그래프·1RM; f Watch/Wear·웹·Apple Health·Strava | 무료 루틴 4·커스텀 7·차트 3개월(헬프센터) vs 스토어 문구 "무제한" 불일치, 피드 끌 수 없음 *(약한 확인)* |
| B3 | **Fitbod** | b 기록 기반 AI 세션 + **근육 회복 맵**; c 장비 프로필로 종목 풀 필터 | TechRadar: 다른 앱 운동이 연속에 섞여 들어옴(검증 안 한 것을 셈) |
| B4 | **Apple Fitness / Activity rings** | d·e 링 3개·어워드·월간 챌린지 | 하루 놓치면 끝나는 연속에 대한 비판 → **watchOS 11 'Pause Rings'**(하루·주·월·최대 90일, 연속 유지) |
| B5 | **Strava** | d·e 세그먼트·리더보드·kudos·트로피 | 2018 히트맵 군사기지 노출, **2020 리더보드 유료화 반발**, 2024 API 제한 반발 |
| B6 | **Duolingo** | e 연속·**Streak Freeze(최대 2)**·XP·리그 — 연속 애니메이션 D7 +1.7 % 등 자체 수치 | 스스로 "초기 뒤엔 손실 회피가 주 동력" 이라 밝힘; L@S '22 연구가 게임 요소 집착(학습 저해) 기록 |
| B7 | **MyFitnessPal** | a 바코드·최근/자주·끼니 복사 | **2022-10 바코드 유료화** 반발(The Verge "egregious"), BMJ NPH 2024 DB 정확도 문제 |
| B8 | **Headspace** | 온보딩 퀴즈 → 추천 코스: 코스 시작 **31 → 63 %**(Irrational Labs) | 활성 일수는 안 늘었음(돌아온 사용자가 코스를 못 찾음) — 온보딩 답을 홈에서 다시 보여 줘야 함 |
| B9 | **토스 만보기** | e 걷기·방문 미션(하루 최대 140원) — '일일 퀘스트' 로 받아들여짐; d 또래 백분위 | 2022-06 상담 데이터 판매 논란(묶음 동의) |
| B10 | **번핏(Burnfit)** (4.8~4.9★ ≈7.1천) | a **세트 복사·지난 운동 조회**·워밍업/드롭/RPE; b 분할별 루틴·공유; c GIF + 기구 필터; d 1RM 추이·SBD 합·달력·주간/월간 볼륨 | 독립 비판 기사 없음 *(미확인)*; 내보내기 언급 없음 |

보조 사실: Samsung Health 2020-07 체중·칼로리 기록 삭제 반발, Google Fit API 2025-06-30 종료(Health Connect 로), WHOOP 2025 업그레이드 유료 전환 반발 — **있던 기능을 빼거나 유료화하는 것이 가장 많이 기록된 반발 원인**이다(G1 보관 기간을 줄이는 방향으로는 가지 말 것).

## 3. 평가에 쓴 체크리스트 (28항목 요약)

1 터치 대상 ≥ 48 dp(M3)/44 pt(HIG)/24 px(WCAG AA)·간격 8 dp · 2 모서리는 12 mm, 중앙 7 mm(Hoober) · 3 글자 ≥ 11, 하단 내비 라벨 12 sp · 4 200 % 확대 · 5 대비 4.5:1/3:1, 라이트·다크 모두 · 6 하단 내비 3~5 · 7 **화면당 주요 행동 하나** · 8 점진 공개 2단계 이하 · 9 선택지 7±2 묶음 · 10 시트: 핸들·스와이프·Done↔Close · 11 피드백 0.1/1/10 s, 400 ms · 12 **자동 진행 타이머는 조절 가능** · 13 깜빡임 금지 · 14 숫자 키패드·라벨 위 · 15 스테퍼 규칙 · 16 **지난 값 자동 채움** · 17 **드래그·스와이프에 대안** · 18 시스템에서 가져올 수 있으면 입력시키지 않기 · 19 확인은 되돌릴 수 없을 때만, 버튼 이름은 결과, 되돌리기 우선 · 20 빈 상태 = 상태+설명+CTA · 21 오류 문장은 원인+해결 · 22 **연속에는 멈춤 경로**(구매 아님) · 23 confirmshaming·잔소리·가짜 긴급·연속 복구 구매 금지 · 24 **검증 안 한 것은 세지도 축하하지도 않기** · 25 Activity 링 모방 금지 · 26 건강 권한은 맥락 안에서, 문구 규칙 · 27 내보내기·백업 제공, 기능 삭제·유료화 금지 · 28 토스 문구 규칙.

---

## 4. v1 평가

### 4.1 측정 (Playwright, 아트보드 18장)

측정 방법: 각 아트보드를 Chromium 으로 열어 ① 탭 가능 요소(a·button·input·label 스위치)의 경계 상자, ② 모든 텍스트 노드의 계산된 글자 크기, ③ 글자색과 실효 배경색(알파 합성)의 대비를 계산했다. 라벨 요소 중 입력을 감싸기만 하는 것(시각 숨김 라벨 포함)은 제외. 정적 측정이라 200 % 확대·다크 모드·실기기 손 크기는 못 본다.

| 항목 | 기준 | v1 | v2 |
|---|---|---|---|
| 탭 대상 44 px 미만 | HIG 44 pt · M3 48 dp · WCAG 2.5.5 | **92곳** (필터 칩 34~36, 세그먼트 38, 닫기 칩 38, 세트 완료 40, RPE 40, 달력 셀 폭 42, '수정' 칩 22, 체중 버튼이 flex 축소로 23) | **0** |
| 글자 12 px 미만 | M3 label-medium 12 · HIG 최소 11 | **149곳** (하단 내비 10, 요일 10, 칩·안내 11) | **0** |
| 본문 대비 4.5:1 미달 | WCAG 1.4.3 · KWCAG 5.4.3 | **30곳** (달력 다른 달·미래 날짜 2.66:1, 보상 띠 흰 글자 3.4~4.2:1) | **0** |

### 4.2 페이지별 발견과 조치

| 페이지 | v1 문제 (기준) | v2 조치 |
|---|---|---|
| P18 홈 | 블록 7개, 주요 행동 '운동하기' 가 카드 속 36 px 칩 (7 하나의 주요 행동·Fitts·Von Restorff·Hick) | '오늘 운동' 카드를 맨 위로, 전폭 48 px **운동 시작하기**; 블록 5개(날씨 줄은 날짜 옆 한 줄로); 소모 kcal 는 통계로 이동 |
| P1 운동 기록 | 달력+연속+날짜 카드+주간 요약 한 화면 (Miller·점진 공개); 셀 폭 42·다른 달 숫자 2.66:1 (1·5); 월 이동 글자 버튼 | **달력 / 통계** 세그먼트로 분리(통계 아트보드 신설); 셀 ≥ 44, 다른 달 공백, 미래 날짜 #6B7363(4.9:1)·비활성; 44 px 아이콘 버튼 + aria-label; '오늘' 점프; 날짜 버튼마다 "11월 5일 목요일, 오늘, 운동함" 라벨 |
| P3 세트 입력 | RPE·메모 항상 노출(8); 완료 40·RPE 40·채우기 40(1); 자동 카운트 안내가 색뿐 | kg·회 두 칸(48 px)·완료 44; **RPE·메모 접기**(지난 값 요약 표시); 자동 카운트는 색+테두리+보조문 12 px; **휴식 +30초 버튼**(12, WCAG 2.2.1); 버튼 하단 고정 |
| P5 완료 | 띠 글자 대비 3.4~4.2(5); '수정' 36(1) | 바탕 #4A6329 + 검정 알약(≥ 7:1); 44 px 버튼; 오운완·업적 버튼 하단 고정(Peak-End) |
| P6 업적 | 요일 10 px·배지 11 px(3); 연속에 멈춤 경로 없음(22) | 12 px; 배지 버튼에 진행도 라벨; **'연속 잠시 멈추기'(최대 14일, 구매 없음)** 행 신설 |
| P13 설정 | Health Connect '준비 중' 행 노출(미구현 기능 노출, 페이지 계획과 불일치); 행 52 | 행 숨김(구현 뒤 26 의 문구 규칙으로); 행 56; 진행 아이콘 통일 |
| P2·P9·P12·P14·P15·P16 시트 | 닫기가 38 px 칩·11 px(1·10); 제목·설명 혼재 | **핸들 + 제목 한 줄 + 44 px X 버튼**(10·28); 라디오·체크 행 56~60; 스위치 터치 높이 44 |
| P8 운동 목록 | 필터 칩 34~36(1); '… n개 더' 가 비활성 글자 | 칩 44, 최소 너비 44, aria-pressed; **'n개 더 보기'** 버튼; 검색 48 |
| P10 추천 루틴 | 카드마다 버튼 2개(7·9); 세그먼트 38 | 카드 탭 = 미리 보기, 버튼 하나 '요일에 넣기'; 세그먼트·칩 44 |
| P11 주간 루틴 | '수정' 칩 22 px(WCAG 2.5.8 24 미달); 드래그만 가능(17); CTA 가 스크롤 안 | 행 전체 탭(60 px) + 44 px 드래그 핸들 + 순서·삭제의 비드래그 경로 문구; **운동 시작하기 하단 고정**(엄지 영역) |
| P4 종목 상세 | 축 글자 10 px; 버튼 스크롤 안 | 12 px; 하단 고정 2버튼; '지난달 기록 더 보기' 44 |
| P7 체중 | 버튼이 flex 축소로 23 px 로 그려짐(실측 결함) | `flex:none` + 하단 고정; 기간 탭 44 |
| P17 물 | 요일 10 px | 12 px; 막대 그래프 aria 라벨 |
| P16 알림 | "끊기기 전날" 손실 프레이밍(23·Duolingo) | "오늘 운동하면 연속 13일" 긍정 프레이밍, "돈으로 되살리기 없음" 명시 |
| 빈 상태 | 없음(20) | **아트보드 신설**: 기록 없음·배지 0·요일 비어 있음·검색 결과 없음 — 상태+설명+CTA |
| 전체 | 하단 내비 라벨 10 px(3); 안내 상자 2~3개/화면; 뒤로 가기 방식 제각각; CTA 문구 제각각(28) | 라벨 12 px·아이콘 24; 안내 상자 화면당 1개; 밀어 올린 화면은 44 px 뒤로 버튼으로 통일; CTA "~하기"(운동 시작하기·저장하기·추가하기·적용하기), 부정은 "닫기" |

### 4.3 그대로 둔 것과 이유

- **영양 링 하나**(홈·식단): 링 모양이지만 Apple 의 Move/Exercise/Stand 세 링이 아니고 단일 진행 링이라 25 에 걸리지 않는다고 봤다. iOS 심사에서 지적되면 막대로 바꾼다.
- **보상 트리거 표**(XP·배지): 24 의 "검증 안 한 것은 세지 않기" 와 저장소 원칙 #1 이 같은 말이라 유지. 자세 점수·카메라 횟수는 보상에 들어가지 않는다.
- **G1 보관 기간 '제한 없음' 기본**: 27(기능 삭제·유료화가 반발의 주원인)이 이 방향을 지지한다. 결정은 사용자 몫(격차 문서 §8).

### 4.4 이 평가가 못 본 것

- 200 % 글자 확대(WCAG 1.4.4·HIG Dynamic Type)와 다크 모드 대비 — 정적 캔버스라 측정 불가. 구현 시 Compose 의 `fontScale 2.0`·다크 테마에서 다시 잰다.
- 응답 시간(0.1/1/10 s·400 ms), 깜빡임, 햅틱 — 구현 뒤 실기기.
- 실제 사용자 테스트(5명 과업 테스트)는 하지 않았다. 체크리스트 평가는 전문가 검토일 뿐 사용자 관찰을 대신하지 않는다.
- 커뮤니티 탭·카메라 세션 화면은 이번 범위 밖.

---

## 5. 구현에 넘길 규칙 (디자인 토큰 수준)

| 항목 | 값 |
|---|---|
| 탭 대상 | 최소 44×44 dp, 목록 행 56~60, 칩 버튼 44(최소 너비 44), 아이콘 버튼 44, 스위치 터치 높이 44(트랙 52×32) |
| 글자 | 하단 내비 12, 보조 12, 본문 14~15, 제목 20~24, 큰 숫자 26~34. 11 미만 금지 |
| 색 | 본문 #1F2618 · 보조 #5E6754(5.9:1) · 비활성 글자 #6B7363(4.9:1) · 칩 글자 #4A6329 · 버튼 #5F7D39 + 흰 글자(4.8:1) · 강조 띠 #4A6329 · 경고 글자 #7A5A10 · 삭제 #9E3D3D |
| 구조 | 화면당 주요 행동 하나를 하단 고정(내비 위), 시트는 핸들+제목 한 줄+44 px 닫기, 밀어 올린 화면은 44 px 뒤로 버튼, 회색 안내 상자는 화면당 하나 |
| 입력 | 숫자 키패드(`inputmode=decimal/numeric`), 라벨 위, 지난 값 자동 채움, 고급 입력(RPE·메모)은 접기 |
| 파괴적 행동 | 세트·종목 삭제는 되돌리기 5 s, 전체 삭제·보관 축소만 확인(버튼 이름은 결과) |
| 보상 | 연속은 목표 요일 기준 + 멈춤 경로(최대 14일, 구매 없음); 알림은 긍정 프레이밍; XP·배지는 수행·기록 사실에만 |
| 문구 | 단일 CTA "~하기", 부정 "닫기/다음에", 시트 제목 한 줄, 추정값엔 '추정' |

---

## 6. 출처

A1 https://www.nngroup.com/articles/ten-usability-heuristics/ · https://www.nngroup.com/articles/response-times-3-important-limits/ — A2 https://developer.apple.com/design/human-interface-guidelines/accessibility (typography, tab-bars, sheets, entering-data, healthkit) — A3 https://developer.android.com/develop/ui/compose/components/navigation-bar · https://support.google.com/accessibility/android/answer/7101858 · https://api.flutter.dev/flutter/material/TextTheme-class.html · https://github.com/material-components/material-components-android/blob/master/docs/components/BottomSheet.md — A4 https://www.w3.org/TR/WCAG22/ — A5 https://a11ykr.github.io/kwcag22/ — A6 https://lawsofux.com/ — A7 https://www.uxmatters.com/mt/archives/2013/02/how-do-users-really-hold-mobile-devices.php · https://www.uxmatters.com/mt/archives/2017/03/design-for-fingers-touch-and-people-part-1.php — A8 https://www.nngroup.com/articles/progressive-disclosure/ · https://www.nngroup.com/articles/confirmation-dialog/ · https://www.nngroup.com/articles/empty-state-interface-design/ · https://www.nngroup.com/articles/input-steppers/ · https://www.nngroup.com/articles/mobile-input-checklist/ — A9 https://jnd.org/the-design-of-everyday-things-revised-and-expanded-edition/ · https://www.cs.umd.edu/~ben/goldenrules.html — A10 https://toss.im/tossfeed/article/uxwriter-interview · https://developers-apps-in-toss.toss.im/design/components — 보조 https://www.deceptive.design/types · https://developer.android.com/health-and-fitness/health-connect/ui/promote

B1 https://www.strong.app/ · https://apps.apple.com/us/app/id464254577 — B2 https://www.hevyapp.com/ · https://help.hevyapp.com/hc/en-us/articles/35119778922263 — B3 https://www.techradar.com/health-fitness/fitbod-app-review — B4 https://9to5mac.com/2024/07/16/close-your-ringsbut-in-watchos-11-its-okay-if-you-dont/ — B5 https://www.bikeradar.com/news/strava-leaderboards-routes-subscription/ · https://www.cyclingnews.com/news/strava-plan-to-restrict-third-party-apps-has-users-in-an-uproar — B6 https://blog.duolingo.com/how-duolingo-streak-builds-habit/ · https://arxiv.org/abs/2203.16175 — B7 https://blog.myfitnesspal.com/barcode-scan-moving-to-myfitnesspal-premium/ · https://nutrition.bmj.com/content/7/1/54 — B8 https://irrationallabs.com/case-studies/headspace-doubled-course-starts/ — B9 https://toss.im/tossfeed/article/toss-pedometer-four-million · https://byline.network/2022/06/10-233 — B10 https://apps.apple.com/kr/app/id1503464984 — 보조 https://www.engadget.com/samsung-health-drops-weight-calorie-intake-tracking-204925298.html · https://developer.android.com/health-and-fitness/guides/health-connect/migrate/fit-apis-end-of-service

측정 스크립트는 세션 작업 폴더에만 두었다(저장소 밖). 내보내기 도구는 `tools/export_gap_prototype.py`.

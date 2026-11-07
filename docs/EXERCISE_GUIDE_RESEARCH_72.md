# 운동 72종 가이드 제작 근거 검토

> Historical initial audit: the partial and single-color findings below describe the initial draft, not the shipped artwork. The final 72-exercise role dataset is [guides.ko.json](../research/exercise_guides/guides.ko.json); see [app integration and verification](EXERCISE_GUIDE_APP_72.md) for the final status.

검토 기준: PR #65, `c7e16633b73f498bb16feb3a655dca9c3fa6f6a0`, 2026-11-07.

전체 72종은 기존 카탈로그 26종과 PR 추가 46종입니다. 기존 가이드 GIF는 16종이므로 새 가이드는 56종이 필요합니다. 기존 16종도 근육 표시를 다시 검토했습니다.

## 근거를 이미지에 적용하는 기준

- 논문의 직접 측정, 유사 동작에서의 측정, 의료·해부학 자료에 근거한 추론을 구분합니다. 종목 이름이 같아도 자세·지지면·그립·저항 방향이 다르면 직접 검증으로 취급하지 않습니다.
- 근전도 신호는 측정 조건과 전극 위치의 영향을 받습니다. 이 자료는 모든 사용자의 활성도나 근육 성장량을 확정하지 않습니다. [근전도 해석 방법론](https://pmc.ncbi.nlm.nih.gov/articles/PMC5758546/)
- 빨간색은 근거가 확인된 일부 근육의 위치만 표시합니다. 색 농도·면적·순위로 활성 강도를 나타내지 않습니다. PR의 숫자 가중치는 생리학적 측정값으로 검증되지 않았습니다.
- 옷에 가려진 근육, 깊은 근육, 힘줄·뼈는 표면에 칠하지 않습니다. 표시가 없다고 비활성 근육이라는 뜻은 아닙니다.
- 근거 검토 통과와 생성 이미지 검수 통과는 별개입니다. 이미지의 자세·해부학적 위치·장비가 맞는지 확인한 뒤 사용해야 합니다. 의료 전문가의 임상 검수나 개인별 운동 처방을 대신하지 않습니다.

## 확인한 주요 수정 사항

- Y 레이즈: 저장소 정의인 바닥 엎드리기를 사용합니다. 테이블 지지 연구와의 차이를 남기고 승모근 하부만 표시합니다.
- 밴드 로우·리버스 플라이: 밴드 고정점이 있는 변형을 사용합니다. 밴드를 양손으로 벌리는 다른 동작으로 바꾸지 않습니다.
- 무릎 사이드 플랭크: Mayo의 발 지지 그림을 무릎 지지 근거로 사용하지 않습니다. NHS의 무릎 지지 자세와 AAOS·해부학 자료를 대조했습니다.
- 시저 크로스: 상체 굴곡을 유지한 수평 교차 동작입니다. 복직근 표시는 해부학적 역할에 근거하며 이 정확한 변형의 직접 근전도 결과로 주장하지 않습니다.
- 기존 니업의 복부 강조, 사이드 크런치의 복직근 강조, 바지 위 엉덩이 강조는 재사용하지 않습니다.
- 힙 스러스트·네발기기 힙 익스텐션·밴드 워크는 선택한 근거 근육이 반바지에 가려져 있으므로 억지로 빨간색을 추가하지 않습니다. 근육 설명은 문구로 제공합니다.

## 종목별 이미지 표시 범위와 출처

아래 표는 표시할 근육의 제한된 목록입니다. 전체 참여 근육 목록이나 활성도 순위가 아닙니다. 연구 대상·부하·변형 차이와 제외 근육은 [전체 감사 데이터](../outputs/exercise-guides-72/muscle-evidence.json)에 보존했습니다.

| 운동 | 이미지에 표시할 수 있는 근육 | 근거의 성격 | 근거 자료 |
|---|---|---|---|
| 기본 스쿼트 | 외측광근 · 내측광근 | 유사 변형·조건 제한 | [자료 1](https://pubmed.ncbi.nlm.nih.gov/29706723/) |
| 런지 | 외측광근 · 내측광근 · 대퇴직근 | 유사 변형·조건 제한 | [자료 1](https://pubmed.ncbi.nlm.nih.gov/29706723/) |
| 바벨 런지 | 외측광근 · 내측광근 | 의료·해부학·전문기관 근거 | [자료 1](https://pmc.ncbi.nlm.nih.gov/articles/PMC11843896/) |
| 사이드 런지 | 대퇴사두근의 노출된 표층부 | 의료·해부학·전문기관 근거 | [자료 1](https://www.acefitness.org/resources/pros/expert-articles/5028/6-lower-body-exercises-to-do-instead-of-squats/) / [자료 2](https://my.clevelandclinic.org/health/body/22816-quad-muscles) |
| 크로스 런지 | 외측광근 · 내측광근 | 의료·해부학·전문기관 근거 | [자료 1](https://my.clevelandclinic.org/health/body/22816-quad-muscles) |
| 바벨 데드리프트 | 대퇴이두근의 노출된 표층부 | 해당 동작 측정 근거 | [자료 1](https://pubmed.ncbi.nlm.nih.gov/28151780/) |
| 굿모닝 | 햄스트링의 노출된 표층부 | 의료·해부학·전문기관 근거 | [자료 1](https://www.acefitness.org/continuing-education/prosource/january-2016/5767/ace-technique-series-romanian-deadlift/) |
| 힙 쓰러스트 | 색칠 없음: 근거 근육이 옷에 가려짐 | 의료·해부학·전문기관 근거 | [자료 1](https://www.acefitness.org/resources/everyone/exercise-library/367/elevated-glute-bridge/) / [자료 2](https://pubmed.ncbi.nlm.nih.gov/28151780/) / [자료 3](https://www.acefitness.org/continuing-education/prosource/january-2016/5767/ace-technique-series-romanian-deadlift/) |
| 의자 스쿼트 | 대퇴직근 · 전경골근 | 유사 변형·조건 제한 | [자료 1](https://www.kais99.org/jkais/journal/Vol23No01/vol23no01p076.pdf) |
| 고블릿 스쿼트 | 내측광근 · 외측광근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.nasm.org/resource-center/exercise-library/goblet-squat) |
| 밴드 스쿼트 | 외측광근 · 내측광근 | 의료·해부학·전문기관 근거 | [자료 1](https://my.clevelandclinic.org/health/body/22816-quad-muscles) |
| 불가리안 스플릿 스쿼트 | 외측광근 · 내측광근 · 대퇴직근 | 해당 동작 측정 근거 | [자료 1](https://link.springer.com/article/10.1186/s13102-025-01306-z) |
| 리버스 런지 | 대퇴직근 | 해당 동작 측정 근거 | [자료 1](https://scholarworks.gvsu.edu/oapsf_articles/189/) |
| 스텝업 | 햄스트링의 노출된 표층부 | 의료·해부학·전문기관 근거 | [자료 1](https://www.acefitness.org/resources/pros/expert-articles/9061/glute-goals-an-evidence-based-glute-workout/) / [자료 2](https://www.ncbi.nlm.nih.gov/sites/books/NBK546688/?report=reader) |
| 레그 프레스 | 대퇴사두근의 노출된 표층부 | 의료·해부학·전문기관 근거 | [자료 1](https://www.nasm.org/resource-center/exercise-library/leg-press) / [자료 2](https://my.clevelandclinic.org/health/body/22816-quad-muscles) |
| 덤벨 루마니안 데드리프트 | 햄스트링의 노출된 표층부 | 의료·해부학·전문기관 근거 | [자료 1](https://www.nasm.org/resource-center/exercise-library/dumbbell-romanian-deadlift) / [자료 2](https://www.ncbi.nlm.nih.gov/sites/books/NBK546688/?report=reader) |
| 밴드 루마니안 데드리프트 | 대퇴이두근 장두의 노출부 | 의료·해부학·전문기관 근거 | [자료 1](https://www.ncbi.nlm.nih.gov/sites/books/NBK546688/?report=reader) |
| 시티드 레그 컬 | 대퇴이두근의 노출된 표층부 · 반건양근의 노출된 원위부 | 의료·해부학·전문기관 근거 | [자료 1](https://www.nasm.org/resource-center/exercise-library/seated-leg-curl) |
| 글루트 브릿지 | 대퇴이두근의 노출된 표층부 | 유사 변형·조건 제한 | [자료 1](https://pubmed.ncbi.nlm.nih.gov/29706723/) |
| 싱글레그 글루트 브릿지 | 지지 다리의 대퇴이두근 | 해당 동작 측정 근거 | [자료 1](https://pmc.ncbi.nlm.nih.gov/articles/PMC5534144/) |
| 네발기기 힙 익스텐션 | 색칠 없음: 근거 근육이 옷에 가려짐 | 의료·해부학·전문기관 근거 | [자료 1](https://www.acefitness.org/resources/everyone/exercise-library/270/quadruped-bent-knee-hip-extensions/) / [자료 2](https://pmc.ncbi.nlm.nih.gov/articles/PMC6088126/) |
| 사이드 밴드 워크 | 색칠 없음: 근거 근육이 옷에 가려짐 | 의료·해부학·전문기관 근거 | [자료 1](https://pmc.ncbi.nlm.nih.gov/articles/PMC6333220/) / [자료 2](https://www.acefitness.org/continuing-education/prosource/september-2013/3459/is-your-exercise-selection-helping-or-hurting-your-clients/) |
| 카프 레이즈 | 비복근 내측두 · 비복근 외측두 | 의료·해부학·전문기관 근거 | [자료 1](https://pmc.ncbi.nlm.nih.gov/articles/PMC10753835/) |
| 오버헤드 프레스 | 삼각근 전면부 · 삼각근 중간부 · 상완삼두근의 노출된 표층부 | 의료·해부학·전문기관 근거 | [자료 1](https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2022.825880/full) |
| 랫풀 다운 | 광배근 · 상완이두근 | 해당 동작 측정 근거 | [자료 1](https://pmc.ncbi.nlm.nih.gov/articles/PMC12452428/) |
| 딥스 | 상완삼두근 외측 표면 · 대흉근 쇄골부 · 삼각근 전면부 | 해당 동작 측정 근거 | [자료 1](https://pmc.ncbi.nlm.nih.gov/articles/PMC9603242/) |
| 덤벨 컬 | 상완이두근 · 상완요골근의 팔꿈치 가까운 부위 | 해당 동작 측정 근거 | [자료 1](https://pmc.ncbi.nlm.nih.gov/articles/PMC6047503/) |
| 바벨 컬 | 상완이두근 · 상완요골근의 팔꿈치 가까운 부위 | 해당 동작 측정 근거 | [자료 1](https://pmc.ncbi.nlm.nih.gov/articles/PMC6047503/) |
| 사이드 레터럴 레이즈 | 삼각근 중간부 | 해당 동작 측정 근거 | [자료 1](https://pubmed.ncbi.nlm.nih.gov/32824894/) / [자료 2](https://rad.uw.edu/muscle-atlas/deltoid) |
| 프런트 레이즈 | 삼각근 전면부 | 해당 동작 측정 근거 | [자료 1](https://pubmed.ncbi.nlm.nih.gov/32824894/) / [자료 2](https://rad.uw.edu/muscle-atlas/deltoid) |
| 업라이트로우 | 삼각근 중간부 · 승모근 상부 | 유사 변형·조건 제한 | [자료 1](https://researchworks.creighton.edu/esploro/outputs/journalArticle/Effect-of-grip-width-on-electromyographic/991005930047202656) / [자료 2](https://rad.uw.edu/muscle-atlas/deltoid) |
| 푸쉬업 | 대흉근 · 상완삼두근 장두의 노출부 · 삼각근 전면부 | 해당 동작 측정 근거 | [자료 1](https://pmc.ncbi.nlm.nih.gov/articles/PMC3916913/) |
| 니 푸쉬업 | 대흉근 · 상완삼두근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.nasm.org/resource-center/exercise-library/modified-push-up) |
| Y 레이즈 | 승모근 하부의 노출부 | 유사 변형·조건 제한 | [자료 1](https://www.jkema.org/download/download_pdf?pid=jkema-9-2-182) / [자료 2](https://www.newyorkortho.com/pdf/return-to-swimming-protocol.pdf) |
| 벽 푸쉬업 | 대흉근 · 상완삼두근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.nth.nhs.uk/resources/standing-exercises/) / [자료 2](https://www.nasm.org/resource-center/exercise-library/incline-push-up) / [자료 3](https://rad.uw.edu/muscle-atlas/pectoralis-major) / [자료 4](https://rad.uw.edu/muscle-atlas/triceps-brachii) |
| 인클라인 푸쉬업 | 대흉근 · 상완삼두근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.nasm.org/resource-center/exercise-library/incline-push-up) / [자료 2](https://rad.uw.edu/muscle-atlas/pectoralis-major) / [자료 3](https://rad.uw.edu/muscle-atlas/triceps-brachii) |
| 파이크 푸쉬업 | 삼각근 전면부 · 상완삼두근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.nasm.org/resource-center/exercise-library/pike-push-up) / [자료 2](https://rad.uw.edu/muscle-atlas/deltoid) / [자료 3](https://rad.uw.edu/muscle-atlas/triceps-brachii) |
| 덤벨 벤치 프레스 | 대흉근 · 삼각근 전면부 · 상완삼두근 | 해당 동작 측정 근거 | [자료 1](https://www.researchgate.net/profile/Marius-Fimland/publication/49746305_A_comparison_of_muscle_activity_and_1-RM_strength_of_three_chest-press_exercises_with_different_stability_requirements/links/0912f5072f6d4587f9000000/A-comparison-of-muscle-activity-and-1-RM-strength-of-three-chest-press-exercises-with-different-stability-requirements.pdf?origin=publication_detail) |
| 덤벨 플로어 프레스 | 대흉근 · 상완삼두근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.acefitness.org/resources/pros/expert-articles/8972/be-a-chest-day-champion-an-evidence-based-approach-to-training-the-chest/?topicScope=workouts) / [자료 2](https://rad.uw.edu/muscle-atlas/pectoralis-major) / [자료 3](https://rad.uw.edu/muscle-atlas/triceps-brachii) |
| 머신 체스트 프레스 | 대흉근 · 상완삼두근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.nasm.org/resource-center/exercise-library/chest-press-machine) / [자료 2](https://rad.uw.edu/muscle-atlas/pectoralis-major) / [자료 3](https://rad.uw.edu/muscle-atlas/triceps-brachii) |
| 밴드 체스트 프레스 | 대흉근 · 상완삼두근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.acefitness.org/continuing-education/certified/july-2025/8897/the-ace-workout-builder-for-resistance-band-workouts/) / [자료 2](https://phl.ascension.org/wellness/fitness/your-exercise-routine-band-chest-press) / [자료 3](https://rad.uw.edu/muscle-atlas/pectoralis-major) / [자료 4](https://rad.uw.edu/muscle-atlas/triceps-brachii) |
| 밴드 숄더 프레스 | 삼각근 전면·중간부의 노출부 · 상완삼두근 | 의료·해부학·전문기관 근거 | [자료 1](https://my.vanderbilthealth.com/to-strengthen-muscles-in-your-shoulders-try-this-simple-exercise/) / [자료 2](https://rad.uw.edu/muscle-atlas/deltoid) / [자료 3](https://rad.uw.edu/muscle-atlas/triceps-brachii) |
| 원암 덤벨 로우 | 동작하는 쪽 광배근 | 의료·해부학·전문기관 근거 | [자료 1](https://health.clevelandclinic.org/dumbbell-rows) / [자료 2](https://rad.uw.edu/muscle-atlas/latissimus-dorsi) |
| 밴드 로우 | 광배근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.newyorkortho.com/pdf/return-to-swimming-protocol.pdf) / [자료 2](https://rad.uw.edu/muscle-atlas/latissimus-dorsi) |
| 시티드 케이블 로우 | 광배근 · 승모근 중부의 노출부 · 상완이두근 | 해당 동작 측정 근거 | [자료 1](https://pmc.ncbi.nlm.nih.gov/articles/PMC449729/) / [자료 2](https://www.acefitness.org/continuing-education/certified/december-2018/7138/ace-sponsored-research-what-is-the-best-back-exercise/) |
| 인버티드 로우 | 광배근 · 승모근 중부의 노출부 | 해당 동작 측정 근거 | [자료 1](https://www.acefitness.org/continuing-education/certified/december-2018/7138/ace-sponsored-research-what-is-the-best-back-exercise/) / [자료 2](https://rad.uw.edu/muscle-atlas/latissimus-dorsi) / [자료 3](https://rad.uw.edu/muscle-atlas/trapezius) |
| 풀업 | 광배근 · 승모근 하부의 노출부 · 상완이두근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.acefitness.org/continuing-education/certified/december-2018/7138/ace-sponsored-research-what-is-the-best-back-exercise/) / [자료 2](https://rad.uw.edu/muscle-atlas/latissimus-dorsi) / [자료 3](https://rad.uw.edu/muscle-atlas/trapezius) / [자료 4](https://www.nasm.org/resource-center/exercise-library/pull-up) / [자료 5](https://rad.uw.edu/muscle-atlas/biceps-brachii) |
| 케이블 페이스 풀 | 삼각근 후면부 · 승모근 중부의 노출부 | 의료·해부학·전문기관 근거 | [자료 1](https://www.nasm.org/resource-center/exercise-library/face-pull) / [자료 2](https://rad.uw.edu/muscle-atlas/deltoid) / [자료 3](https://rad.uw.edu/muscle-atlas/trapezius) |
| 밴드 리버스 플라이 | 삼각근 후면부 · 삼각근 중간부 · 승모근 상부 | 해당 동작 측정 근거 | [자료 1](https://pmc.ncbi.nlm.nih.gov/articles/PMC5873332/) |
| I-Y-T 레이즈 | 승모근 중부 · 승모근 하부 | 유사 변형·조건 제한 | [자료 1](https://www.acefitness.org/continuing-education/certified/december-2018/7138/ace-sponsored-research-what-is-the-best-back-exercise/) / [자료 2](https://rad.uw.edu/muscle-atlas/trapezius) |
| 케이블 트라이셉스 푸시다운 | 상완삼두근 장두 · 상완삼두근 외측두 | 유사 변형·조건 제한 | [자료 1](https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2020.00112/full) / [자료 2](https://www.acefitness.org/resources/everyone/exercise-library/3/triceps-pressdown/) |
| 플랭크 | 복직근 · 외복사근 | 해당 동작 측정 근거 | [자료 1](https://pubmed.ncbi.nlm.nih.gov/30856100/) / [자료 2](https://pubmed.ncbi.nlm.nih.gov/22436839/) |
| 무릎 플랭크 | 복직근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.mayoclinic.org/healthy-lifestyle/fitness/in-depth/core-strength/art-20546851) / [자료 2](https://openstax.org/books/anatomy-and-physiology-2e/pages/11-4-axial-muscles-of-the-abdominal-wall-and-thorax) |
| 사이드 플랭크 | 외복사근 | 해당 동작 측정 근거 | [자료 1](https://pubmed.ncbi.nlm.nih.gov/22436839/) |
| 무릎 사이드 플랭크 | 외복사근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.wwl.nhs.uk/media/.leaflets/6214d2b8e08b32.04972952.pdf) / [자료 2](https://www.orthoinfo.org/bodypart/back/spine-conditioning-program/) / [자료 3](https://openstax.org/books/anatomy-and-physiology-2e/pages/11-4-axial-muscles-of-the-abdominal-wall-and-thorax) |
| 데드버그 | 복직근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.nasm.org/resource-center/exercise-library/dead-bug) |
| 버드독 | 허리 척추기립근 영역 | 해당 동작 측정 근거 | [자료 1](https://pubmed.ncbi.nlm.nih.gov/22436839/) |
| 팔로프 프레스 | 외복사근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.acefitness.org/resources/everyone/exercise-library/332/standing-anti-rotation-press/) / [자료 2](https://openstax.org/books/anatomy-and-physiology-2e/pages/11-4-axial-muscles-of-the-abdominal-wall-and-thorax) |
| 파머스 캐리 | 복직근 · 외복사근 | 해당 동작 측정 근거 | [자료 1](https://intjexersci.com/files/ijes/vol17/iss1/7.pdf) |
| 수트케이스 캐리 | 외복사근 · 복직근 | 해당 동작 측정 근거 | [자료 1](https://intjexersci.com/files/ijes/vol17/iss1/7.pdf) |
| 싱글레그 애브도미널 프레스 | 복직근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.mayoclinic.org/healthy-lifestyle/fitness/in-depth/core-strength/art-20546851) / [자료 2](https://openstax.org/books/anatomy-and-physiology-2e/pages/11-4-axial-muscles-of-the-abdominal-wall-and-thorax) |
| 스탠딩 사이드 크런치 | 외복사근 | 의료·해부학·전문기관 근거 | [자료 1](https://openstax.org/books/anatomy-and-physiology-2e/pages/11-4-axial-muscles-of-the-abdominal-wall-and-thorax) |
| 스탠딩 니업 | 대퇴직근의 노출된 원위 근복 | 의료·해부학·전문기관 근거 | [자료 1](https://www.elsevier.com/resources/anatomy/muscular-system/muscles-of-lower-limb/rectus-femoris-muscle/17868) / [자료 2](https://www.cuh.nhs.uk/patient-information/hip-strengthening-exercises/) |
| 행잉 레그 레이즈 | 복직근 · 외복사근 | 해당 동작 측정 근거 | [자료 1](https://pubmed.ncbi.nlm.nih.gov/25111163/) |
| 크런치 | 복직근 | 해당 동작 측정 근거 | [자료 1](https://pmc.ncbi.nlm.nih.gov/articles/PMC155519/) / [자료 2](https://www.acefitness.org/getfit/studies/bestworstabexercises.pdf) |
| 레그 레이즈 | 복직근 | 해당 동작 측정 근거 | [자료 1](https://pubmed.ncbi.nlm.nih.gov/27065536/) |
| 시저 크로스 | 복직근 | 의료·해부학·전문기관 근거 | [자료 1](https://pmc.ncbi.nlm.nih.gov/articles/PMC155519/) / [자료 2](https://openstax.org/books/anatomy-and-physiology-2e/pages/11-4-axial-muscles-of-the-abdominal-wall-and-thorax) |
| 리버스 크런치 | 복직근 | 해당 동작 측정 근거 | [자료 1](https://www.acefitness.org/getfit/studies/bestworstabexercises.pdf) |
| 바이시클 크런치 | 복직근 · 외복사근 | 해당 동작 측정 근거 | [자료 1](https://www.acefitness.org/getfit/studies/bestworstabexercises.pdf) / [자료 2](https://openstax.org/books/anatomy-and-physiology-2e/pages/11-4-axial-muscles-of-the-abdominal-wall-and-thorax) |
| 짐볼 크런치 | 복직근 · 외복사근 | 해당 동작 측정 근거 | [자료 1](https://pubmed.ncbi.nlm.nih.gov/17530978/) |
| 캡틴체어 니 레이즈 | 복직근 · 외복사근 | 해당 동작 측정 근거 | [자료 1](https://www.acefitness.org/getfit/studies/bestworstabexercises.pdf) |
| 러시안 트위스트 | 외복사근 · 복직근 | 의료·해부학·전문기관 근거 | [자료 1](https://www.nasm.org/resource-center/exercise-library/russian-twist) |

## 산출물과 검증 범위

[가이드 문구 72종](../outputs/exercise-guides-72/GUIDE_COPY_KO.md) · [구조화 문구](../outputs/exercise-guides-72/guides.ko.json) · [근육 감사 원문](../outputs/exercise-guides-72/muscle-evidence.json)

원본 자료의 그림을 앱 자산으로 복제하지 않습니다. 참고 자료의 재배포 허가가 확인된 것은 아니므로 새 인체 이미지를 제작합니다. 생성 결과·재시도·미해결 항목은 제작 기록에 별도로 남깁니다.

앱 구현·기존 자산 교체·PR 게시·배포는 하지 않았습니다. 자세 평가 지원 여부는 가이드 제작과 별개입니다.

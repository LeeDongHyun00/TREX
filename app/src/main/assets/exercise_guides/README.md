# 운동 방법 시범

로컬 UI 구현용 시범 3종목. Gym Visual 파일을 내려받거나 사용하지 않았다.

| 앱 종목 | 패키지 파일 | 기존 로컬 원본 |
|---|---|---|
| 기본 스쿼트 | `squat.gif` | `outputs/exercise-guides/v2/squat-front.gif` |
| 런지 | `lunge.gif` | `outputs/exercise-guides/v2/lunge-right-shoulder.gif` |
| 덤벨 컬 | `dumbbell-curl.gif` | `outputs/exercise-guides/v2/dumbbell-curl-front.gif` |

기존 ImageGen 시안의 시작·끝 자세를 번갈아 보여주는 GIF(640×640, 2프레임)다.
연속 촬영 영상이나 전문가 검수를 거친 운동 교육 자료가 아니다. 로컬 작업에서
배치·재생·일시정지 동작을 확인하기 위한 자산이며, 배포용 연속 동작 시범으로 교체할 여지가 있다.
원본 파일과 바이트가 동일하다. `outputs/`는 Git 추적 대상이 아니므로 앱 실행은 여기에 복사한 파일만 사용한다.

바벨 스쿼트·바벨 컬·바벨/사이드/크로스 런지에 이 파일을 대신 쓰지 않는다.
새 자산을 넣을 때 `ExerciseGuides`에 정확한 종목 이름과 파일 경로를 등록한다.
동작 그림의 각도는 촬영 방향 안내 또는 자세 판정의 근거로 사용하지 않는다.

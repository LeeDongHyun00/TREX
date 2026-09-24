# 폰 렙 검증 Gate A — 실행 절차 (spec §61)

프로토콜(무엇을 재고 왜 그 기준인가)은 `REP_VALIDATION.md`, 판정 기준은 `docs/REP_ENGINE_DESIGN.md` §1·§7.
이 문서는 **순서**만 적는다. 명령은 `research/external_rep_replay/` 에서 실행한다(윈도우는 `python` = 연구용 가상환경의 파이썬).

Gate A 는 개발 데이터다 — 결과로 상수·정책을 바꿀 수 있으므로 성능 주장으로 쓰지 않는다. 서비스 판정은 Gate B 에서만 한다.

## 0. 시작 전 한 번

1. PC 가 폰을 본다:
   ```
   python pull_phone.py devices
   ```
   `unauthorized` 면 폰 화면의 USB 디버깅 허용을 누른다. adb 를 못 찾으면 `--adb <경로>` 를 준다
   (윈도우 기본 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`).
2. **설치 전에 백업한다** (CLAUDE.md '설치 함정'). 기기에 다른 키로 서명된 빌드가 있으면 `install -r` 이 거부되고,
   지우고 다시 깔면 운동 기록·기준선이 사라진다. 지우는 일이 생기기 전에:
   ```
   python pull_phone.py pull --out ../../data/phone/backup-<날짜>
   adb shell "run-as com.example.trex_kotlin ls shared_prefs"
   adb exec-out "run-as com.example.trex_kotlin cat shared_prefs/<파일>.xml" > ../../data/phone/backup-<날짜>/<파일>.xml
   ```
   (`exec-out` 은 윈도우 줄바꿈 변환을 피한다. 디버그 빌드라 `run-as` 가 된다.)
3. 빌드·설치 — 서명 거부가 나면 **멈추고** 백업을 확인한 뒤에만 삭제를 결정한다:
   ```
   gradlew.bat :app:assembleDebug
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

## 1. 세션 날

1. 검증 모드 켜기 — 세션 화면 위에 "검증 모드 · 횟수 숨김 · 자동 진행 꺼짐 · 음성 꺼짐" 이 보여야 한다:
   ```
   python pull_phone.py validation on
   python pull_phone.py validation status
   ```
   켜는 시점: 표시 파일은 운동 단계가 바뀔 때마다 다시 읽는다. 앱을 다시 켤 필요는 없다.
2. 계획표 — 무작위 순서로 만들고 그 순서대로 찍는다(채점기는 같은 종목의 로그를 시각 순서로 계획 행과 짝짓는다):
   ```
   python rep_validation_plan.py --csv gateA --persons 3 --out-dir ../../data/phone_rep
   ```
3. 세트마다 (`REP_VALIDATION.md` §3 운영 규칙이 우선한다):
   - 집계자는 앱 화면을 보지 않고 센다. 런지는 왼·오른 걸음을 따로(`tally_left`·`tally_right`).
   - 세트는 **✓ 로만 끝낸다** — 검증 모드는 목표에 닿아도 넘기지 않는다. 마지막 반복 뒤 1~2 초 가만히 있다가 ✓
     (세트의 마지막 반복은 상단 확정에 다음 하강이 필요해 놓칠 수 있다 — 그 몫을 재려는 것이다).
   - 세로·가로를 둘 다 찍는다. 세로는 발끝까지 화면에 들어오게 — 들어오지 않으면 그대로 찍되 계획표 비고에 적는다
     (잘림 빈도가 측정 대상이다, §19).
   - 완료 화면에 집계 값을 넣는다(런지는 min(왼, 오른)). 집계표 칸은 세트 직후 채운다.
4. 세션이 끝나면 회수 — 기기에서 아무것도 지우지 않는다:
   ```
   python pull_phone.py pull --out ../../data/phone/<날짜>
   ```
5. **검증 모드 끄기** — 켜 둔 채 두면 평소 운동에서 숫자가 숨고 자동 진행·음성이 꺼진다:
   ```
   python pull_phone.py validation off
   ```

## 2. 보고서

```
python gate_a.py run ../../data/phone/<날짜> --plan ../../data/phone_rep/plan_gateA.csv --out ../../data/phone/<날짜>_gateA
```

`<out>/report.md` 에 설계 §7 나가는 조건 점검표, 틀린 세트와 자동 원인 후보(사람이 확인해야 ✓), 프레이밍(종목 × 세로/가로 잘림 비율),
피처 무결성(로그 좌표로 다시 계산한 신호 = 로그 신호)이 있다. 무결성이 ✗ 면 좌표 기반 재분석 결과를 믿지 않는다 — 먼저 원인을 본다.

파이프라인 자체가 도는지는 폰 없이 확인할 수 있다:
```
python gate_a.py dry-run --out <임시 폴더>
```

## 3. 올리지 않는 것

`data/` 는 `.gitignore` 대상이다. 세트 로그·캡처·보고서의 세트별 표는 사람의 동작 기록이라 저장소에 올리지 않는다 —
올린다면 집계 수치만, 사람을 식별할 수 없는 형태로.

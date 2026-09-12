# iOS 자세 기능 — Windows·Mac 협업 규약

**작성일: 2026-09-12**

**상태(2026-09-13): Mac 환경 인수인계 반영, M0~M2 구현 착수 범위 확정. KMP/iOS 구현은 아직 시작하지 않았다.**

**GitHub 기준 소스: `5237ba9` — 분석한 로컬 `5c7118e`와 추적 파일 트리가 동일하다.**

**저장소: [LeeDongHyun00/TREX](https://github.com/LeeDongHyun00/TREX)**

GitHub에 코드·설계·실행 결과의 요약을 공유하고 두 기기에서 각각 작업한다. 같은 브랜치에 동시에 쓰지 않고 역할별 브랜치의 변경을 통합 브랜치로 합친다. 자동 동기화나 주기적인 작업 실행은 설정하지 않는다. 작업을 시작하거나 상대 변경이 필요할 때 fetch하고 상태 문서를 읽는다.

## 1. 브랜치와 소유권

| 브랜치 | 용도 | 작성 주체 |
|---|---|---|
| `codex/ios-posture-kmp` | 검토한 설계·공통 API·통과한 작업을 모으는 통합 기준 | 현재 Windows 작업을 통합 담당으로 제안 |
| `codex/ios-kmp-windows` | P0 재생 기준·성능 계측, P1 공통 계산 추출, Android 연결 | Windows Codex |
| `codex/ios-kmp-mac` | Mac 환경 확인, Kotlin/Native 검증, Swift/iOS 연결 | Mac Codex |

이름이 같은 로컬 브랜치가 이미 있으면 새로 만들거나 덮어쓰지 말고 upstream·미커밋·미푸시 변경을 먼저 확인한다. `main` 또는 과거 자세 브랜치에서 새 작업을 시작하지 않는다. 기존 `feature/posture-coach-reliability`는 이번 협업 설정으로 변경하지 않는다.

같은 통합 커밋에서 출발한 뒤 각 작업 브랜치만 push한다. 통합 브랜치의 최신 변경은 작업 브랜치에 merge한다. 공유된 이력에는 force-push·reset으로 덮어쓰기를 하지 않는다. 역할을 바꿔야 하면 상태 문서와 PR 설명에 파일 소유권 변경을 먼저 명시한다.

**공유 이력의 출발점:** 첫 push는 로컬 조상 커밋의 `docs/bridge-sides.full-sweep.v1.json`(118.34 MB) 때문에 GitHub에서 거절됐다. 원격 `5237ba9`와 로컬 `5c7118e`의 트리 해시가 모두 `8a7f55908aad3526af0879b9337a069fe4222c45`임을 확인한 뒤, 원격 커밋 위에 설계·협업 문서 커밋만 옮겼다. 앱 코드는 동일하다. 기존 로컬 이력과 첫 문서 커밋은 Windows의 `codex/ios-kmp-local-history-20260912`에 보존했다. 이 보존 브랜치는 공유하지 않으며, 큰 파일을 다시 들여오는 해당 로컬 이력을 협업 브랜치에 merge하지 않는다. Mac에서 재현할 코드 기준은 GitHub에서 받을 수 있는 `5237ba9`다.

## 2. 처음 읽을 문서

1. [AGENTS.md](../AGENTS.md)
2. [POSTURE_HANDOFF.md](POSTURE_HANDOFF.md) — 최신 설계/구현 상태 구분
3. [IOS_POSTURE_KMP_DESIGN.md](IOS_POSTURE_KMP_DESIGN.md) — ADR-056, 아직 제안 상태
4. 이 문서와 [Windows 상태](ios-kmp/WINDOWS_STATUS.md), [Mac 상태](ios-kmp/MAC_STATUS.md)
5. 필요한 세부 규약은 [KOTLIN_PORTING_SPEC.md](../research/aihub_fitness/KOTLIN_PORTING_SPEC.md)

Mac의 다음 구현 작업은 [MAC_IMPLEMENTATION.md](ios-kmp/MAC_IMPLEMENTATION.md)를 따른다. 이 문서의 M0~M2가 초기의 환경 검토 전용 범위를 대체한다. ADR의 평가 엔진 계약은 유지한다.

설계의 `posture-core`, `TrexPosture`, 입력 DTO와 이벤트 이름은 아직 실제 API가 아니다. 문서만 보고 Mac에서 별도 엔진이나 임시 판정 로직을 만들지 않는다. 공통 API가 커밋되면 그 **정확한 커밋**을 상태 문서에 기록하고 사용한다.

## 3. Mac에서 저장소 받기

새로 받는 경우, 비어 있거나 새로 만들 위치에서 실행한다.

```bash
git clone --branch codex/ios-kmp-mac https://github.com/LeeDongHyun00/TREX.git TREX-ios-kmp
cd TREX-ios-kmp
git status --short --branch
git log -1 --oneline
```

기존 체크아웃이 있고 작업 트리가 깨끗한 경우:

```bash
git status --short --branch
git fetch origin --prune
git switch --track origin/codex/ios-kmp-mac
```

이미 로컬 `codex/ios-kmp-mac`가 있으면 마지막 명령 대신 `git switch codex/ios-kmp-mac` 후 `git pull --ff-only`를 사용한다. 미커밋 또는 로컬 작업이 있으면 강제로 전환하지 않는다. 그 작업을 별도 커밋/작업 트리로 보존하고 새로운 협업 체크아웃을 사용한다.

`gradlew`는 현재 Git 실행 비트가 100644다. 초기 Mac 검사에서는 파일 모드 변경 없이 `bash ./gradlew ...`로 실행할 수 있다. 실행 비트를 고치는 작업은 별도 변경으로 기록한다. Android SDK의 `local.properties`는 각 기기에서 만들며 Git에 올리지 않는다.

## 4. 역할과 먼저 할 일

| 영역 | 주 담당 | 다른 쪽이 할 수 있는 일 |
|---|---|---|
| 기존 Android 엔진·`app/`·재생 기준 | Windows | Mac은 읽고 Native 이식 장애를 보고 |
| `posture-core/commonMain`, 공통 DTO/파서/정책 | Windows | Mac은 API 소비 검증, 변경 필요 사항을 별도 PR로 제안 |
| 루트 Gradle·버전 카탈로그·공통 모듈 빌드 | Windows | Mac은 필요한 타깃/프레임워크 설정을 작은 PR로 제안 |
| `iosApp/`, Podfile/잠금, Swift 카메라·센서·TTS | Mac | Windows는 공통 계약 관점에서 검토 |
| `posture-core/iosMain`, Native 테스트 리소스 로더 | Mac, P1 모듈 생성 후 | 공통 DTO 변경은 Windows와 커밋 기준을 맞춤 |
| 공통 재생 픽스처 원본·규칙·모델 | Windows 통합 담당 | Mac 결과를 보고 대조. 원본 임계값/모델을 독자 수정하지 않음 |
| `WINDOWS_STATUS.md` / `MAC_STATUS.md` | 각자 자기 파일만 작성 | 상대 파일을 읽고 필요한 입력 확인 |
| ADR·정본 스펙·인수인계 | 통합 담당 | Mac은 검토 결과를 자기 상태/PR에 남김 |

**Windows의 다음 범위:** 설계 P0에서 기존 테스트·프레임/이벤트 기준을 확인한 뒤 P1 공통 계산 모듈을 추출한다. 이번 브랜치 공유 작업에서는 실행하지 않았다.

**Mac의 다음 범위:** 환경 검토 `6a569ec`를 수신했다. [M0~M2 구현 계획](ios-kmp/MAC_IMPLEMENTATION.md)에 따라 KMP 의존성 없는 SwiftUI 진단 앱, 카메라·센서, MediaPipe 관절 출력까지 구현한다. 아직 평가 엔진이 없음을 표시하며 점수·횟수·교정 음성은 만들지 않는다. Intel/Xcode·실기기 실행 제약을 실제 명령/결과로 기록한다.

이후 Mac은 Windows가 공개한 P1 커밋으로 Kotlin/Native 계산을 검증하고, P2 facade를 받아 M3/P3의 평가 기능을 연결한다. P3 중 OS 어댑터 부분만 선행하는 결정이며 P1/P2의 완료 조건을 생략하지 않는다. Mac은 `iosApp/` 하위 ignore·프로젝트·Pod·문서·테스트를 소유하고, 공통 Gradle 변경은 Windows 담당으로 유지한다.

## 5. 변경을 주고받는 절차

1. 시작할 때 `git status`로 자기 작업을 확인하고 `git fetch origin --prune`를 실행한다.
2. 통합 브랜치와 상대 상태를 읽는다. 필요한 커밋이 아직 통합되지 않았다면 PR/상태에 정확한 SHA를 적고 의존성을 명시한다.
3. 담당 파일을 수정하고 적절한 검사 후 **자기 상태 파일**을 갱신한다. 변경 요약·기준 API 커밋·명령/결과·미실행 항목·상대에게 필요한 일을 적는다.
4. 목적별 작은 커밋을 자기 작업 브랜치에 push하고 PR의 base를 **`codex/ios-posture-kmp`**로 지정한다. `main`으로 PR을 만들지 않는다.
5. 통합 담당은 상대 PR과 검사 결과를 읽고 merge한다. 소스 이력이 남도록 merge commit을 기본으로 한다. 이 문서 작성만으로 향후 PR이 자동 승인/머지되는 것은 아니다. 이번 2026-09-13 구현 준비 업데이트는 사용자의 브랜치 갱신 요청에 따라 Mac 문서 커밋을 merge하고 착수 문서/자산 검사 도구를 통합 브랜치에 직접 공유한다. 이후 앱 구현은 PR 절차를 따른다.
6. 다음 작업 시작 전에 자기 브랜치에서 `git merge origin/codex/ios-posture-kmp`로 통합 변경을 가져온다. 충돌은 담당 영역과 테스트를 확인해 해결하며 상대 변경을 통째로 선택해 버리지 않는다.

공통 API를 바꾸는 PR에는 최소한 `변경한 타입/메서드`, `소비 쪽 변경 필요 여부`, `통과한 플랫폼`, `검증하지 못한 플랫폼`을 적는다. Windows JVM 성공만으로 Native 성공이라고 쓰지 않는다.

Mac에서 예를 들어 다음 통합 변경을 받는 명령은 다음과 같다. 미커밋을 보존한 후 실행한다.

```bash
git fetch origin --prune
git switch codex/ios-kmp-mac
git merge origin/codex/ios-posture-kmp
```

## 6. 무엇을 공유하고 무엇이 없는가

공유 대상은 코드·설계·정본 규칙/모델·기존 추적 테스트 픽스처·재현 명령·검증 요약이다. 실제로 push한 커밋만 다른 기기에서 받을 수 있다. 말로 한 결정을 다음 작업에 필요하면 상태 문서나 PR에 남긴다.

- `research/aihub_fitness/outputs*/`, 원천 AIHub 데이터, 실제 운동 `.jsonl` 로그는 `.gitignore` 대상이다. Mac에 자동으로 존재하지 않는다.
- 실제 관절 재생 자료가 추가로 필요하면 익명화·공유 가능 여부를 확인한 테스트 픽스처를 별도로 선정한다. 무시 규칙을 해제하거나 `git add -f`로 개인 로그/원본 데이터를 올리지 않는다.
- `local.properties`, 서명키, 인증정보, 개인 설정, 로컬 SDK 경로는 공유하지 않는다. Xcode가 생성한 개인 상태·빌드 산출물도 iOS 프로젝트 추가 시 해당 ignore 규칙으로 제외한다.
- 모델/규칙의 해시와 실제 테스트 입력이 다르면 결과 비교 전에 그 차이를 기록한다.
- Mac 환경 검사 결과는 `6a569ec`에 있다. 아직 KMP 모듈, iOS 앱, Native 빌드 결과는 없다. 자산 확인 도구는 [구현 계획 §5](ios-kmp/MAC_IMPLEMENTATION.md)에 있다.

## 7. Mac Codex에 전달할 시작 문구

아래 문구는 Mac의 TREX 저장소 작업에서 사용한다. 여기서 Mac 작업을 원격으로 시작하거나 메시지를 보낸 것은 아니다.

> TREX의 `codex/ios-kmp-mac` 브랜치에서 기존 작업을 보존하고 원격 Mac 변경과 `origin/codex/ios-posture-kmp`를 가져오세요. `AGENTS.md`, `docs/POSTURE_HANDOFF.md`, ADR와 협업 규약, 양쪽 상태, `docs/ios-kmp/MAC_IMPLEMENTATION.md`를 읽으세요. 환경 검토만 하던 범위가 M0~M2 구현으로 갱신됐습니다. 자산 검사 도구를 실행한 뒤 M0 SwiftUI 진단 앱부터 만들고, M1 카메라·센서, M2 MediaPipe 관절 출력으로 이어가세요. KMP/Swift 판정 엔진은 대신 만들지 말고 `평가 엔진 연결 전` 상태를 유지하세요. 기기 실행 장애와 실제 검증 범위를 구분해 `MAC_STATUS.md`와 `iosApp/README.md`에 기록하고 Mac 브랜치에 커밋·푸시하세요. PR base는 `codex/ios-posture-kmp`입니다. P1/P2 API가 공개되면 해당 SHA를 기준으로 M3 공통 엔진 연결을 진행하세요.

## 8. 상태 공유 양식

각 상태 파일에는 아래 항목을 유지한다. 자기 파일의 변경 자체를 가리키는 자기참조 커밋 SHA 대신 **검증한 코드 커밋**을 기록한다. 아직 검증하지 않았으면 `미실행`이라고 적는다.

```text
갱신일 / 담당 기기 / 작업 브랜치
가져온 통합 기준 커밋
현재 단계 / 맡은 파일
완료한 변경 / 사용한 API 계약 커밋
실행한 명령과 결과 / 실제 검증 코드 커밋
실행하지 못한 검사와 이유
다음 작업 / 상대에게 필요한 입력
관련 PR 또는 커밋
```

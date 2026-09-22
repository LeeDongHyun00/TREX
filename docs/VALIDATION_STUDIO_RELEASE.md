# TREX 검증 스튜디오 배포

## 배포 대상

- 브랜치: `codex/validation-studio`
- 태그: `validation-v1.0.0-preview`
- 앱: **TREX 검증**, `com.example.trex_kotlin.validation`
- APK: `TREX-validation-1.0.0-preview.apk`, 앱 버전 `1.0.0-validation-preview`
- APK 구현 소스: `4b589aa3ec5f2a6b25e67c6853c291915f64a486`
- APK SHA-256: `10e463346aade730c2652c6bc2401a458ef85f4043e137d0a4ccb1a44a0e938c`
- APK 크기: 79,552,719바이트. Android 8.0 이상 ARM64/ARMv7, 개발 서명.

이번 배포 커밋은 위 구현 소스에 README와 배포 문서만 추가한다. APK는 이미 검증한 동일 파일을 사용하며 재빌드로 바꾸지 않는다. 릴리스 태그의 문서 커밋과 APK 구현 커밋을 `BUILD_INFO.json`에 구분한다. 앱·공유 엔진·모델·빌드 설정에 차이가 없는지 배포 전 확인한다.

## 제공 파일

공개 사전 릴리스에 APK, `SHA256SUMS.txt`, `BUILD_INFO.json`을 첨부한다. 앱 자체가 수집한 사람 영상·좌표·라벨·개인 파일은 업로드하지 않는다. README의 직접 다운로드는 해당 릴리스 파일을 가리킨다. 일반 TREX와 trex_v2의 브랜치/릴리스는 수정하지 않는다.

## 확인 결과와 한계

기존 구현에서 JVM 514건과 Android 에뮬레이터 5건을 통과했다. APK 서명·패키지·버전·ABI와 원본 SHA-256을 배포 전에 다시 확인한다. GitHub Actions 통과나 외부 코드 리뷰 완료를 주장하지 않는다. 사람 촬영 정확도, 물리 기기 카메라/디코더 호환성, 실시간 음성 교정 효과는 미검증이다.

게시 후 릴리스의 공개 상태·대상 커밋·첨부 크기/해시·README 다운로드 경로를 확인한다. 초기 설치/촬영 실패 등 중대한 결함을 확인하면 해당 사전 릴리스의 APK 배포를 중단하고 새 버전으로 수정한다. 사용자의 기존 운동 앱이나 저장 자료를 삭제하는 복구 절차를 요구하지 않는다.

## 관련 문서

[앱 역할과 다운로드](../README.md) · [설계와 사용 방법](VALIDATION_STUDIO.md)

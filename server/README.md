# TREX Server

T-REX 백엔드. Spring Boot 4 / Java 17 / Gradle. 담당 범위는 사용자 인증, 세션 관리, 기록 동기화, 날씨 API 연동, 운동 추천 로직뿐이며 AI 추론(자세 인식, 음식 인식)은 전부 클라이언트(Android)에서 온디바이스로 수행합니다. 자세한 규칙은 저장소 루트의 `CLAUDE.md`를 참고하세요.

## 실행 환경

- Java 17
- Gradle Wrapper 포함: 별도 Gradle 설치 불필요
- 로컬 개발 DB: H2 인메모리 (PostgreSQL 호환 모드)

## 로컬 설정

`src/main/resources/application-example.yml`을 복사해 `application.yml`로 저장하고, JWT 시크릿 등 값을 채웁니다. `application.yml`은 `.gitignore` 대상이라 커밋되지 않습니다.

## 실행하기

```bash
./gradlew bootRun
```

## 빌드 확인

```bash
./gradlew build
```

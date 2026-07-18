# OpenPlan Backend

OpenPlan MVP1 백엔드 Repository

## 기술 스택

- Java 21 LTS
- Spring Boot
- PostgreSQL 16 (pgvector)
- Spring Data JPA
- Flyway
- Spring Security + OAuth2 + JWT
- Swagger / Springdoc OpenAPI

## 개발환경

개발환경 설정과 실행 방법은 [DEVELOPMENT.md](./DEVELOPMENT.md)를 참고합니다.

## 로컬 DB 실행

PostgreSQL은 Docker Compose로 실행합니다. 기본 포트는 `5433`입니다.

```bash
docker compose up -d
```

DB 상태 확인:

```bash
docker compose ps
```

DB 종료:

```bash
docker compose down
```

## 백엔드 실행

Spring Boot 애플리케이션은 로컬에서 실행합니다.

```bash
./gradlew bootRun
```

Windows PowerShell:

```powershell
.\gradlew.bat bootRun
```

## API 문서

Swagger 설정 후 아래 주소에서 API 문서를 확인합니다.

```text
http://localhost:8080/swagger-ui/index.html
```

## Git 브랜치 규칙

- `main`: 배포 가능한 안정 버전
- `feature/#이슈번호-기능이름`: 기능 개발
- `fix/#이슈번호-버그내용`: 버그 수정

모든 작업은 GitHub Issue를 기준으로 진행하고, `main` 브랜치에는 Pull Request를 통해 병합합니다.

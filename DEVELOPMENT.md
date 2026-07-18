# OpenPlan Backend 개발환경

## 개발환경 공유 방식

MVP1 개발 단계에서는 PostgreSQL만 Docker Compose로 실행한다. Spring Boot 백엔드는 로컬에서 실행해 개발과 디버깅 편의성을 유지한다.

## 필수 설치

| 항목 | 권장 버전 | 용도 |
| --- | --- | --- |
| Git | 최신 안정 버전 | 소스 코드 관리 |
| Java | 21 LTS | Spring Boot 실행 |
| Docker Desktop | 최신 안정 버전 | PostgreSQL 실행 |
| IntelliJ IDEA | 최신 안정 버전 | 백엔드 개발 |
| psql 또는 DBeaver | 선택 | DB 확인 |

## 최초 설정

### 클론 경로 주의 (필수)

**저장소는 한글과 공백이 없는 경로에 클론한다.** 예: `C:\dev\openplan`

한글이나 공백이 포함된 경로(예: `C:\Users\사용자\바탕 화면\OpenPlan`)에서는 `./gradlew test`가 `ClassNotFoundException`으로 **전건 실패한다.** Windows 기본 로케일에서 JVM이 `sun.jnu.encoding=MS949`로 기동되어 경로를 해석하지 못하는 것이 원인이며, 이 값은 JVM 기동 후에는 변경할 수 없다. ASCII 경로로 옮기면 즉시 해소된다.

빌드 산출물 잠금을 피하기 위해 OneDrive 동기화 폴더도 피한다.

```bash
git clone https://github.com/Seeds-OpenPlan/openplan-backend.git
cd openplan-backend
```

환경변수 예시 파일을 복사한다.

```bash
cp .env.example .env
```

Windows PowerShell에서는 아래 명령을 사용한다.

```powershell
Copy-Item .env.example .env
```

`.env`의 실제 비밀값은 각자 로컬에서만 관리한다. `.env` 파일은 GitHub에 올리지 않는다.

## 설치 확인

Java 설치 확인:

```powershell
java -version
javac -version
echo $env:JAVA_HOME
```

정상 예시:

```text
openjdk version "21.x.x" LTS
javac 21.x.x
C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot\
```

Docker 설치 확인:

```powershell
docker --version
docker compose version
```

정상 예시:

```text
Docker version ...
Docker Compose version ...
```

Windows에서 Docker Desktop을 사용하려면 WSL2가 필요하다. Docker Desktop 실행 시 WSL 업데이트 안내가 나오면 관리자 PowerShell에서 아래 명령을 실행한다.

```powershell
wsl --update
wsl --shutdown
```

WSL이 설치되어 있지 않다는 메시지가 나오면 아래 명령을 실행한 뒤 PC를 재시작한다.

```powershell
wsl --install
```

설치 후 Docker Desktop을 다시 실행하고 `docker compose version`이 출력되는지 확인한다.

## DB 실행

PostgreSQL은 Docker Compose로 실행한다.

```bash
docker compose up -d
```

DB 상태 확인:

```bash
docker compose ps
```

정상 예시:

```text
openplan-postgres   pgvector/pgvector:pg16   ...   Up ... (healthy)   0.0.0.0:5433->5432/tcp
```

`STATUS`에 `healthy`가 표시되면 PostgreSQL 컨테이너가 정상 실행 중인 상태다.

DB 종료:

```bash
docker compose down
```

DB 데이터까지 초기화:

```bash
docker compose down -v
```

`docker compose down -v`는 로컬 DB 데이터를 삭제하므로 필요한 경우에만 사용한다.

## DB 접속 정보

기본 로컬 DB 정보는 `.env.example` 기준으로 통일한다.

| 항목 | 값 |
| --- | --- |
| Host | localhost |
| Port | 5433 (컨테이너 내부 5432) |
| Database | openplan |
| Username | openplan |
| Password | openplan1234 |

## 백엔드 실행

백엔드는 로컬에서 실행한다.

```bash
./gradlew bootRun
```

Windows PowerShell:

```powershell
.\gradlew.bat bootRun
```

백엔드 로컬 주소:

```text
http://localhost:8080
```

정상 로그 예시:

```text
HikariPool-1 - Start completed.
Tomcat started on port 8080
Started OpenplanBackendApplication
```

`bootRun`은 서버를 계속 실행하는 명령이므로 Gradle 진행률이 `80% EXECUTING`처럼 유지되는 것이 정상이다. 서버를 종료하려면 실행 중인 터미널에서 `Ctrl + C`를 누른다.

## Swagger

Swagger는 아래 주소에서 확인한다.

```text
http://localhost:8080/swagger-ui/index.html
```

현재 Controller/API가 없으면 Swagger 화면에 아래 문구가 표시될 수 있다.

```text
No operations defined in spec!
```

이는 Swagger가 정상 동작하지만 아직 문서화할 API가 없다는 의미다. Controller가 추가되면 API 목록이 자동으로 표시된다.

## 자주 발생하는 상황

### `docker` 명령을 찾을 수 없음

Docker Desktop이 설치되지 않았거나 PATH가 아직 반영되지 않은 상태일 수 있다. Docker Desktop 설치 후 새 PowerShell을 열고 다시 확인한다.

```powershell
docker --version
```

### Docker Desktop에서 WSL 업데이트 안내가 나옴

관리자 PowerShell에서 아래 명령을 실행한 뒤 Docker Desktop을 재시작한다.

```powershell
wsl --update
wsl --shutdown
```

### Docker Desktop에서 실행 중인 프로세스 안내가 나옴

`Lingering processes detected` 안내가 나오면 `Stop processes`를 눌러 기존 Docker 프로세스를 종료한 뒤 다시 실행한다.

### `./gradlew test`가 `ClassNotFoundException`으로 전부 실패함

저장소가 한글 또는 공백이 포함된 경로에 있는 경우다. Windows 기본 로케일에서 JVM이 `sun.jnu.encoding=MS949`로 기동되어 경로를 해석하지 못한다.

저장소를 한글과 공백이 없는 경로(예: `C:\dev\openplan`)로 옮기면 해소된다. `sun.jnu.encoding`은 JVM 기동 후 변경할 수 없으므로 Gradle 옵션이나 환경변수로는 해결되지 않는다.

### 5433 포트가 이미 사용 중임

로컬에 다른 PostgreSQL이 실행 중이면 포트가 충돌할 수 있다. 이 경우 `.env`에서 아래 값을 변경한다.

```env
POSTGRES_PORT=5434
DB_PORT=5434
```

이후 다시 실행한다.

```bash
docker compose up -d
```

## 개발 규칙

- `.env`는 커밋하지 않는다.
- `.env.example`은 필요한 환경변수가 추가될 때 함께 갱신한다.
- Docker는 PostgreSQL 실행에만 사용한다.
- Spring Boot 백엔드는 로컬에서 실행한다.
- DB 스키마 변경은 Flyway 마이그레이션(`src/main/resources/db/migration`)으로 관리한다.
- PR 제출 전 로컬 실행과 관련 테스트 통과 여부를 확인한다.

# OpenPlan Backend — 배포 이미지 (D-11 단일 오리진 구성의 BE 컨테이너)
#
# 멀티스테이지: 빌드 산출물(jar)만 런타임 이미지로 옮긴다. Gradle·소스·테스트는 최종 이미지에
# 남지 않는다 — 이미지 크기와 공격 표면을 함께 줄이기 위함이다.
#
# 빌드:  docker build -t openplan-backend .
# 실행:  docker run -p 8080:8080 --env-file .env openplan-backend

# ── 1단계: 빌드 ───────────────────────────────────────────────────────────────
FROM gradle:8.14-jdk21 AS builder
WORKDIR /build

# 의존성 선캐싱 — 빌드 스크립트만 먼저 복사해 dependencies를 받아두면, 소스만 바뀐 재빌드에서
# 이 레이어가 그대로 재사용된다.
COPY build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon || true

COPY src ./src
# -x test: 이미지 빌드는 배포 산출물을 만드는 단계다. 테스트는 CI에서 DB를 띄우고 돌린다
# (여기서 돌리면 Testcontainers/DB 의존 때문에 이미지 빌드가 환경을 타게 된다).
RUN gradle bootJar --no-daemon -x test

# ── 2단계: 런타임 ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# 비root 실행 — 컨테이너 탈출 시 피해를 줄인다.
RUN useradd --system --uid 10001 --create-home openplan
USER openplan

COPY --from=builder --chown=openplan:openplan /build/build/libs/*.jar app.jar

# 운영 기본값. local 프로파일(dev 시드 포함)로 뜨지 않도록 명시한다 — application.yaml의
# 기본값이 local이므로, 이 지정이 빠지면 배포 이미지가 dev 설정으로 뜬다.
ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=Asia/Seoul \
    JAVA_OPTS=""

EXPOSE 8080

# exec 형식 + sh -c: JAVA_OPTS를 런타임에 주입할 수 있게 하면서 PID 1이 JVM이 되도록 한다
# (그래야 docker stop의 SIGTERM이 JVM에 직접 전달돼 graceful shutdown이 동작한다).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

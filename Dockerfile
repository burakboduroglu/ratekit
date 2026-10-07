# One Dockerfile for all three services. Build from the repository root, naming the service:
#   docker build --build-arg SERVICE=ingest -t ratekit-ingest .
# The context must be the root because every service depends on the parent pom and `common`.

ARG SERVICE

# ---- build: compile the service and the modules it needs ----
FROM maven:3.9.16-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /build

# poms first, sources after: the layers above this line only change when a pom changes
COPY pom.xml .
COPY common/pom.xml common/
COPY ingest/pom.xml ingest/
COPY rating/pom.xml rating/
COPY billing/pom.xml billing/

COPY common/src common/src
COPY ${SERVICE}/src ${SERVICE}/src

# tests need containers of their own, so they run in CI, not while building an image
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${SERVICE} -am package -DskipTests

# ---- runtime: only a JRE and the jar ----
FROM eclipse-temurin:24.0.2_12-jre
ARG SERVICE
LABEL org.opencontainers.image.title="ratekit-${SERVICE}" \
      org.opencontainers.image.source="https://github.com/burakboduroglu/ratekit" \
      org.opencontainers.image.licenses="Apache-2.0"

# never run as root inside the container
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /build/${SERVICE}/target/${SERVICE}-*-SNAPSHOT.jar app.jar
USER app

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

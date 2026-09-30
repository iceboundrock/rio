# The backend as a container, for the `full` profile of ../docker-compose.cdc.yml. Everyday development
# runs it on the host instead (./start.sh). Build context: this directory; .dockerignore lists what enters it.
# 25 is the toolchain JDK that build.gradle.kts pins; bump both tags with it.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
# Tests are not run here: they need Docker themselves, and ./verify.sh runs them. The cache mount keeps
# the Gradle distribution and dependencies between builds.
RUN --mount=type=cache,target=/root/.gradle ./gradlew installDist --no-daemon --quiet --console=plain

FROM eclipse-temurin:25-jre
RUN useradd --system rio
COPY --from=build /src/build/install/rio-backend /opt/rio-backend
USER rio
EXPOSE 8080
# The start script `installDist` generates, so the JVM gets applicationDefaultJvmArgs from build.gradle.kts.
ENTRYPOINT ["/opt/rio-backend/bin/rio-backend"]

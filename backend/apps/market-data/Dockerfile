# Build context is backend/, not this directory -- a Gradle multi-module build needs
# every module's build.gradle.kts and settings.gradle.kts to resolve project(":libs:...")
# dependencies, not just this app's own sources.
#
#   docker build -f apps/market-data/Dockerfile -t exchange/market-data .
#   (run from backend/, or use docker compose --profile full build)

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
# Tests already ran in CI before this image is built; running them again here would just
# repeat work and require Kafka/MongoDB to be reachable during the image build, which
# they are not.
RUN ./gradlew :apps:market-data:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# The Spring Boot Gradle plugin disables the plain jar by default, so build/libs holds
# exactly one file: the repackaged, executable boot jar.
COPY --from=build /workspace/apps/market-data/build/libs/*.jar app.jar

# Its actual job -- events in, candles out -- is not HTTP. This port serves only
# actuator's health endpoint: a liveness probe's side door, see application.yml's
# server.port comment and health.ConsumerLivenessHealthIndicator.
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "app.jar"]

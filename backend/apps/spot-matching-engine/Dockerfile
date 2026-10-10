# Build context is backend/, not this directory -- a Gradle multi-module build needs
# every module's build.gradle.kts and settings.gradle.kts to resolve project(":libs:...")
# dependencies, not just this app's own sources.
#
#   docker build -f apps/spot-matching-engine/Dockerfile -t exchange/spot-matching-engine .
#   (run from backend/, or use docker compose --profile full build)

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
# Tests already ran in CI before this image is built; running them again here would
# just repeat work and require Kafka/MinIO to be reachable during the image build,
# which they are not.
RUN ./gradlew :apps:spot-matching-engine:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# The Spring Boot Gradle plugin disables the plain jar by default, so build/libs holds
# exactly one file: the repackaged, executable boot jar.
COPY --from=build /workspace/apps/spot-matching-engine/build/libs/*.jar app.jar

# Its actual job -- commands in, events out -- is still Kafka-only, nothing published
# for that. This port serves only actuator's health endpoint: a liveness probe's side
# door, see MatchingEngineApplication's javadoc and application.yml's server.port comment.
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]

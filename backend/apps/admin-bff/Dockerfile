# Build context is backend/, not this directory -- a Gradle multi-module build needs
# every module's build.gradle.kts and settings.gradle.kts to resolve project(":libs:...")
# dependencies, not just this app's own sources.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :apps:admin-bff:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/apps/admin-bff/build/libs/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]

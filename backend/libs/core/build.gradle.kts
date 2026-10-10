// Shared Kafka plumbing, the API-response/exception conventions every HTTP-facing app
// (open-api, web-bff) renders errors through, and RateLimiter. All three are "every app
// needs exactly one of these, never a per-app copy" concerns, which is what a module is
// for here.
//
// This module has Spring on it (unlike :libs:common) because these ARE Spring
// components -- but it is a plain java-library, not a Spring Boot app: it has no
// main class and produces no runnable jar.

plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        // Pulls in the Spring Boot BOM so versions here match the apps exactly,
        // without applying the Boot plugin (which would try to build a fat jar).
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    api(project(":libs:contracts"))
    api(project(":libs:common"))

    api(libs.spring.kafka)
    implementation("org.springframework:spring-context")
    // @RestControllerAdvice, ResponseEntity, MethodArgumentNotValidException -- for
    // HttpApiExceptionHandler. implementation, not api: every consumer already brings its
    // own spring-boot-starter-web, so nothing needs this exposed transitively.
    implementation("org.springframework:spring-web")
    // ResponseBodyAdvice lives here, not in spring-web, for HttpApiResponseBodyAdvice.
    implementation("org.springframework:spring-webmvc")
    // HttpServletResponse, for reading the status code HttpApiResponseBodyAdvice wraps.
    // compileOnly: every consumer's own spring-boot-starter-web brings the real Tomcat
    // implementation at runtime; this module never touches a container directly.
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    // The apps get slf4j transitively from their Boot starter; a plain library does
    // not, so it must ask for the logging API itself.
    implementation("org.slf4j:slf4j-api")
    // StringRedisTemplate for RateLimiter. implementation: consumers inject RateLimiter
    // itself, never StringRedisTemplate directly -- it stays fully encapsulated.
    implementation(libs.spring.boot.starter.data.redis)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // MockMvc, so HttpApiExceptionHandlerTest proves Spring routes each exception to its handler.
    testImplementation("org.springframework:spring-test")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
}

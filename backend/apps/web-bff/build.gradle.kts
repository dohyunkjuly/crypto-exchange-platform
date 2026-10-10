plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":libs:core"))
    implementation(project(":libs:persistence"))
    implementation(project(":libs:domain:user"))
    implementation(project(":libs:domain:wallet"))
    implementation(project(":libs:domain:fee"))
    implementation(project(":libs:domain:spot"))
    implementation(project(":libs:domain:copy")) // copy trading: following a leader, from the web
    // Candles and the 24h ticker. The one dependency here backed by MongoDB rather than
    // MySQL, so this app now needs Mongo reachable to start -- see its application.yml.
    implementation(project(":libs:domain:marketdata"))

    implementation(libs.spring.boot.starter.web)
    // The live market socket: a WebSocket endpoint, and its own consumer of
    // exchange.spot.events.v1 to feed it. See the marketws package.
    implementation(libs.spring.boot.starter.websocket)
    // Verification and, later, security-alert email. See common/mail.
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.kafka)
    // Not just transitively via libs:core's RateLimiter -- web-bff now keeps state of its
    // own in Redis: the short-lived challenge between a correct password and a correct
    // 2FA code. See LoginChallengeStore.
    implementation(libs.spring.boot.starter.data.redis)
    implementation(project(":libs:contracts"))
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.springdoc.openapi)
    // BCryptPasswordEncoder for AuthService.
    implementation(libs.spring.security.crypto)
    // Verifying Google's ID token. See auth/google.
    implementation(libs.spring.security.oauth2.jose)

    // @Getter on BffHttpErrorCode.
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
}

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":libs:core"))         // also brings spring-kafka transitively
    implementation(project(":libs:persistence"))  // Flyway migrations + JPA/MySQL driver
    implementation(project(":libs:domain:spot"))  // SpotOrder, SpotTrade, SpotSymbol
    implementation(project(":libs:domain:wallet")) // BalanceService, FeeSweepService
    // UserRepository, to resolve fees@exchange once at startup. The wallet domain keeps no
    // dependency on user (see its build file), so composing the two is the app's job.
    implementation(project(":libs:domain:user"))
    // The nightly fee-tier job (fee.FeeTierJobs): volumes from spot, families from user, tiers
    // here. Composing the three is the app's job, as with treasury above.
    implementation(project(":libs:domain:fee"))
    // spring-boot-starter-data-jpa comes transitively from libs:persistence's own `api`.

    // starter-web + starter-actuator together are the one exception to "no HTTP
    // endpoints of its own": a liveness probe's side door, not a second public
    // interface -- see health.ConsumerLivenessHealthIndicator (and spot-matching-
    // engine's copy of the same class, for why it's duplicated rather than shared).
    // Actuator alone has no servlet container to serve HTTP with at all; starter-web
    // supplies one. This app does NOT set spring.main.web-application-type: none (see
    // application.yml's server.port comment for why): actuator's management server
    // only ever exists when the main context is itself a web application, on any port.
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.web)

    testImplementation(libs.spring.boot.starter.test)
}

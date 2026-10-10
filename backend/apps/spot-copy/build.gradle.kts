plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":libs:core"))          // also brings spring-kafka and the contracts
    implementation(project(":libs:persistence"))   // Flyway migrations + JPA/MySQL driver
    implementation(project(":libs:domain:copy"))   // follows, lead orders, closing an account
    implementation(project(":libs:domain:spot"))   // OrderService: a copy is an ordinary order
    implementation(project(":libs:domain:wallet")) // the balances a copy is sized from
    implementation(project(":libs:domain:fee"))    // OrderService stamps the follower's rate
    implementation(project(":libs:domain:user"))   // the gate: a follower who may not trade stops copying

    // The health probe's side door, as in spot-settlement: actuator needs a servlet container.
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.web)

    testImplementation(libs.spring.boot.starter.test)
}

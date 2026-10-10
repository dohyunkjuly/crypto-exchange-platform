plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":libs:contracts"))
    implementation(project(":libs:common"))

    // This app's actual job -- commands in, events out -- has no HTTP endpoints of its
    // own. Its only interface for that is Kafka.
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.kafka)

    // starter-web + starter-actuator together are the one exception: a liveness probe's
    // side door, not a second public interface -- see health.ConsumerLivenessHealthIndicator.
    // Actuator alone has no servlet container to serve HTTP with at all; starter-web
    // supplies one. This app does NOT set spring.main.web-application-type: none (see
    // application.yml's server.port comment for why): actuator's management server only
    // ever exists when the main context is itself a web application, on any port, so
    // this app IS a minimal servlet web app -- just one with nothing registered on it
    // beyond actuator's own health endpoint.
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.web)

    // Snapshots live in object storage; only a pointer to them goes on Kafka.
    implementation(libs.aws.s3)

    testImplementation(libs.spring.boot.starter.test)
}

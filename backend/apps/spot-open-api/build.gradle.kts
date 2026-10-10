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
    implementation(project(":libs:domain:copy")) // what a lead or a copy account's order must pass
    implementation(project(":libs:domain:marketdata"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.springdoc.openapi)

    // @Getter on OpenApiErrorCode.
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
}

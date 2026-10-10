// The fee domain: the published schedule, the volume rollup that feeds it, and each user's
// resolved tier.
//
// Its own module rather than part of spot, because the same concepts apply to futures and
// margin -- separate ladders, separate volume, one shape. Every table carries a `product`
// column for exactly that reason.
//
// No dependency on user or wallet. user_id is a plain column with no foreign key.

plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    api(project(":libs:common"))
    api(libs.spring.boot.starter.data.jpa)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // AssertJ and Mockito, for FeeQueryServiceTest. Added when fee resolution moved here
    // out of OrderService: the rule is now read by the order path and shown to the user
    // before they commit, so it is worth testing on its own rather than only through
    // order placement.
    testImplementation(libs.spring.boot.starter.test)
}

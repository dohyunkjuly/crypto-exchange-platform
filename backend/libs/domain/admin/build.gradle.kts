// Staff: who they are, their sessions, and the log of what they did. docs/reference/admin.md.
//
//   com.exchange.admin.identity   the admin, role, TOTP seed
//                    .session    live sessions, PENDING until the second factor
//                    .audit      append-only log of every admin write
//
// Only entities, repositories and the audit service. The login flow lives in admin-bff, as
// web-bff owns its own: nothing else will ever log an admin in.

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
    implementation(project(":libs:common"))
    implementation(libs.spring.boot.starter.data.jpa)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.spring.boot.starter.test)
}

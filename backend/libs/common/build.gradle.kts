// Shared, framework-free domain code. No Spring here on purpose: this is the
// module both apps depend on, and plain Java keeps it fast to test.

plugins {
    `java-library`
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

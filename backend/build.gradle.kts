// Root build file. It builds nothing itself -- it states the rules every module
// in the backend inherits, so the modules cannot drift apart.

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.protobuf) apply false
}

subprojects {
    group = "com.exchange"
    version = "0.1.0-SNAPSHOT"

    // Applies to any subproject that turns out to be a Java project, without
    // forcing the Java plugin onto ones that aren't.
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                // Pinned to an LTS. Gradle itself may run on a newer JVM; this is
                // the JDK the code is *compiled and tested* against, and it is
                // identical on your laptop and in CI.
                languageVersion = JavaLanguageVersion.of(21)
            }
        }

        tasks.withType<Test>().configureEach {
            // Performance benchmarks are real, committed, permanent test code -- not
            // scratch files -- but they are slow (some build a 2-million-order book in
            // memory) and need live Kafka/MinIO. Running them on every `test` would make
            // the normal dev loop and CI pay minutes for numbers nobody asked for right
            // now. The perf module's tasks (all named perf*) run ONLY these -- and are
            // therefore excluded from this exclusion, or they would both include and exclude
            // the same tag and silently run nothing.
            //
            // "seed" is the same arrangement for market-data's seedMarketData task, which
            // writes several thousand candles into a local MongoDB on purpose. That must
            // never happen as a side effect of `build`.
            if (!name.startsWith("perf") && name != "seedMarketData") {
                useJUnitPlatform {
                    excludeTags("performance", "seed")
                }
            } else {
                useJUnitPlatform()
            }
        }
    }

    tasks.matching { it.name == "bootRun" }.configureEach {
        (this as JavaExec).workingDir = rootProject.projectDir
    }
}

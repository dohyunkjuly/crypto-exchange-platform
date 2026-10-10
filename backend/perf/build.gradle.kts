// Everything performance, in one place: what the system does under load, measured against the
// running stack and written into one report. docs/reference/performance.md says what the
// numbers mean; docs/reference/performance-report.md is what this writes.
//
//   docker compose --profile full up -d        the stack, single broker
//   ./gradlew perf                             every benchmark, then the report
//
// `main` is the report: a record type every benchmark writes, and the renderer that turns the
// records into markdown. `test` holds the benchmarks: `engine` boots a real matching engine
// in-process over Kafka and measures it alone; the rest meet the stack as a customer, a bot and
// an operator do.

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// A library plus tests, not an application: nothing here boots. The two mains (renderer,
// cleanup) are run by their own JavaExec tasks, so Boot must not go looking for "the" one.
tasks.bootJar { enabled = false }
tasks.resolveMainClassName { enabled = false }
tasks.jar { enabled = true }

dependencies {
    implementation(project(":libs:contracts"))
    implementation(project(":libs:common"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.apache.kafka:kafka-clients")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.apache.kafka:kafka-clients")
    testImplementation(libs.mysql.connector)
    // The engine benchmarks start the real engine in this JVM.
    testImplementation(project(":apps:spot-matching-engine"))
    testImplementation(libs.spring.boot.starter)
    testImplementation(libs.spring.kafka)
}

// Three chunks, by what each needs running. `./gradlew perf` runs all three in this order, then
// the report; each also runs alone and rewrites only its own rows.
//
//   perfStack     the exchange end to end: API, click to fill, settlement, sockets
//   perfEngine    the matching engine alone: throughput, stops, snapshots, recovery, memory
//   perfCluster   a broker killed mid-stream; on the three-broker overlay only

fun Test.benchmarkDefaults() {
    group = "performance"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("performance") }
    // A benchmark that cannot complete leaves its row empty; it does not stop the others.
    ignoreFailures = true
    testLogging {
        events("passed", "failed", "skipped", "standardOut")
        showStandardStreams = true
    }
    outputs.upToDateWhen { false }
    finalizedBy("perfReport")
}

/** The exchange as a customer, a bot and an operator meet it. Needs the full stack up. */
tasks.register<Test>("perfStack") {
    benchmarkDefaults()
    description = "Chunk: the exchange end to end -- API, click to fill, settlement, sockets."
    filter {
        excludeTestsMatching("com.exchange.perf.engine.*")
        excludeTestsMatching("com.exchange.perf.BrokerLossBenchmark")
    }
}

/**
 * The engine alone: a real engine booted in this JVM over Kafka. After perfStack, never before
 * -- these leave a million events behind that settlement and market-data then have to read,
 * which would make a fill timed afterwards measure the backlog.
 */
tasks.register<Test>("perfEngine") {
    benchmarkDefaults()
    description = "Chunk: the matching engine alone -- throughput, stops, snapshots, recovery, memory."
    filter { includeTestsMatching("com.exchange.perf.engine.*") }
    mustRunAfter("perfStack")
    // The stack's own engine would consume the same commands and publish a second set of
    // events, and its settlement, market-data and copier would chew through every event the
    // benchmark makes: all of it contention, none of it the engine. They are paused for the
    // duration and started again after -- finalizedBy, so also after a failure -- and catch up
    // on the backlog in their own time (Stack.awaitDrained waits for that before the next
    // end-to-end run).
    dependsOn("pauseStackConsumers")
    finalizedBy("resumeStackConsumers")
    // The memory benchmark builds a two-million-order book; the default test heap is not sized for it.
    maxHeapSize = "3g"
    // A fresh JVM per class: the memory benchmark reads the heap counters, which another
    // class's leftovers or JIT code would pollute, and the throughput ones should not compete
    // with each other's background threads.
    setForkEvery(1L)
}

/** What three brokers buy. Skips itself unless PERF_TOPOLOGY=cluster on docker-compose.perf.yml. */
tasks.register<Test>("perfCluster") {
    benchmarkDefaults()
    description = "Chunk: a broker killed mid-stream (three-broker overlay, PERF_TOPOLOGY=cluster)."
    filter { includeTestsMatching("com.exchange.perf.BrokerLossBenchmark") }
    mustRunAfter("perfStack", "perfEngine")
}

val stackConsumers = listOf("spot-matching-engine", "spot-settlement", "market-data", "spot-copy")
val composeFull = listOf("docker", "compose", "-f", rootProject.projectDir.parentFile.resolve("docker-compose.yml").path, "--profile", "full")

tasks.register<Exec>("pauseStackConsumers") {
    description = "Stops the stack's engine and event consumers while the engine benchmarks run."
    commandLine(composeFull + "stop" + stackConsumers)
    mustRunAfter("perfStack")
}

tasks.register<Exec>("resumeStackConsumers") {
    description = "Starts them again."
    commandLine(composeFull + "start" + stackConsumers)
}

/** Cancels every order the engine benchmarks ever rested; see ClearBenchmarkOrders. */
tasks.register<JavaExec>("perfClean") {
    group = "performance"
    description = "Cancels the engine benchmarks' resting orders on the running stack."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.exchange.perf.tools.ClearBenchmarkOrders"
}

/** Turns build/perf/results.jsonl into docs/reference/performance-report.md. */
tasks.register<JavaExec>("perfReport") {
    group = "performance"
    description = "Renders the performance report from the benchmarks' records."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.exchange.perf.report.ReportRenderer"
    args(rootProject.projectDir.parentFile.resolve("docs/reference/performance-report.md").path,
         project.projectDir.resolve("build/perf").path)
    mustRunAfter("perfStack", "perfEngine", "perfCluster")
}

/**
 * One benchmark alone, then the report: `./gradlew perfOrderToFill`. Its rows are replaced and
 * every other row stays as the last run left it, so a number can be re-measured in a minute
 * without the half hour the whole set takes. `./gradlew tasks --group performance` lists them.
 */
fun benchmark(task: String, testClass: String, pausesStack: Boolean, what: String) {
    tasks.register<Test>(task) {
        group = "performance"
        description = what
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags("performance") }
        filter { includeTestsMatching("com.exchange.perf.$testClass") }
        maxHeapSize = "3g"
        testLogging {
            events("passed", "failed", "skipped", "standardOut")
            showStandardStreams = true
        }
        outputs.upToDateWhen { false }
        if (pausesStack) {
            dependsOn("pauseStackConsumers")
            finalizedBy("resumeStackConsumers")
        }
        finalizedBy("perfReport")
    }
}

// Against the running stack.
benchmark("perfApi", "ApiLatencyBenchmark", false, "Signed order placement and depth reads over the public API.")
benchmark("perfOrderToFill", "OrderToFillBenchmark", false, "From a buy through web-bff to the socket saying FILLED.")
benchmark("perfSettlement", "SettlementBenchmark", false, "Fills a second into MySQL, and how far balances trail the book.")
benchmark("perfSockets", "SocketsBenchmark", false, "Market sockets one web-bff serves at once.")
// The engine alone, the stack's own engine and consumers paused around it.
benchmark("perfThroughput", "engine.ThroughputBenchmark", true, "Commands a second on one partition: the ceiling, a crossing mix, the spread.")
benchmark("perfStops", "engine.ConditionalOrderThroughputBenchmark", true, "What stop cascades cost the partition.")
benchmark("perfSnapshots", "engine.SnapshotOverheadBenchmark", true, "Throughput lost to snapshots at each snapshot-every.")
benchmark("perfRecovery", "engine.RecoveryBenchmark", true, "Seconds until a restarted engine answers.")
// In memory only: no Kafka, nothing to pause.
benchmark("perfMemory", "engine.MemoryFootprintBenchmark", false, "Heap per resting order and snapshot build time, by book depth.")

/**
 * Every benchmark, then the report: `./gradlew perf`. Needs the compose stack up; set
 * PERF_TOPOLOGY=cluster when it runs on docker-compose.perf.yml, so the report fills its
 * second column.
 */
tasks.register("perf") {
    group = "performance"
    description = "Runs every performance benchmark against the running stack and writes docs/reference/performance-report.md."
    dependsOn("perfStack", "perfEngine", "perfCluster")
    finalizedBy("perfReport")
}

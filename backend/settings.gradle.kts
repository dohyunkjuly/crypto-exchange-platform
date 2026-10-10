pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle download the JDK named in the toolchain block automatically, so a new
    // machine only needs *some* JDK to bootstrap, not the exact one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "backend"

// Multiple Spring Boot apps in one build. One split at the top: `apps/` is what
// deploys, `libs/` is what they share.
//
//   gradle :apps:spot-open-api:bootRun    run one app
//   gradle build                          build and test all of them
//
// The `spot-` prefix is deliberate: futures is coming, and futures matching is a
// genuinely different engine (mark price, margin, liquidation, position netting), so
// it will get its own. web-bff has NO prefix, on purpose -- it serves one client (the
// Next.js app), and that client has one login and one nav bar showing both products.
// Splitting it would force the frontend to talk to two backends and duplicate auth
// against both, which is precisely the work a BFF exists to absorb.
include(
    // Plumbing. Knows nothing about trading.
    ":libs:contracts",    // protobuf-generated types, from src/main/proto
    ":libs:common",       // money math, Kafka topic names -- plain Java, no Spring
    ":libs:core",         // shared Kafka plumbing
    ":libs:persistence",  // the `exchange` DB: Flyway migrations, JPA/driver dependencies

    // The business. One module per domain, grouped under libs/domain so they do not
    // interleave alphabetically with the plumbing above. wallet, spot and futures
    // land here alongside user.
    ":libs:domain:user",    // identity, sessions, auth audit log
    ":libs:domain:wallet",  // assets, chains, balances, the ledger
    ":libs:domain:fee",     // the fee schedule, volume rollups, tier assignments
    ":libs:domain:spot",    // markets, orders, trades
    ":libs:domain:admin",   // staff: identity, sessions, the audit log
    ":libs:domain:copy",    // copy trading: leaders, follows, and what each copy did
    // The odd one out: MongoDB, not MySQL/JPA, because it holds a derived read model
    // (candles) rather than a system of record. See its build file.
    ":libs:domain:marketdata", // candles, and the 24h ticker derived from them

    ":apps:spot-open-api",       // Spring Boot :8080 -- public API: account, wallet, trading
    ":apps:web-bff",             // Spring Boot :8081 -- backend-for-frontend for web/
    ":apps:admin-bff",           // Spring Boot :8083 -- backend-for-frontend for admin/, staff only
    ":apps:spot-matching-engine", // Spring Boot -- the order book. No HTTP; Kafka in, Kafka out.
    ":apps:spot-settlement",     // Spring Boot -- the events.v1 consumer. Kafka in, DB out. No HTTP.
    ":apps:spot-copy",           // Spring Boot -- copy trading. events.v1 in, followers' orders out. No HTTP.
    ":apps:market-data",         // Spring Boot -- the other events.v1 consumer. Kafka in, Mongo out. No HTTP.

    ":perf"                      // the performance harness and its report; not an app, not a lib
)

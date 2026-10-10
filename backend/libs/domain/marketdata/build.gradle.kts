// Market data: candles, and the 24h ticker derived from them.
//
//   com.exchange.marketdata.candle   the OHLCV buckets themselves, and reads over them
//   com.exchange.marketdata.ticker   the rolling 24h summary, computed from candles
//
// The one domain module that is NOT JPA/MySQL. Everything else under libs/domain holds a
// system of record -- balances, orders, the ledger -- where a lost row is lost money.
// This module holds a *derived read model*: every candle in it can be thrown away and
// rebuilt by replaying exchange.spot.events.v1 from the beginning, because spot_trades
// (MySQL) remains the actual record of what happened. Different guarantees, different
// store.
//
// The cost, stated plainly since it is not free: any app depending on this needs a
// MongoDB connection to start, on top of the MySQL one it already had. That is why the
// write side lives in apps/market-data alone and only the READ services are here --
// per AGENTS.md, "does more than one app need it?" Both web-bff and
// spot-open-api render market data; only market-data writes it.

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
    // Money math (Money.multiply and the 1e8 scale every price/quantity here uses).
    api(project(":libs:common"))
    // api, not implementation: Candle is a Spring Data document and callers in both apps
    // hold it directly, so the annotations must be on their compile classpath too.
    api(libs.spring.boot.starter.data.mongodb)
    // The market stream (com.exchange.marketdata.stream): the shared WebSocket fan-out both
    // web-bff and spot-open-api register. Reads the event topic, renders frames, holds sockets.
    implementation(project(":libs:contracts"))
    implementation(libs.spring.kafka)
    implementation(libs.spring.boot.starter.websocket)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.spring.boot.starter.test)
}

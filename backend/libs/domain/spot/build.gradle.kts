// The spot domain: the markets, the orders placed on them, and the trades that result.
//
//   com.exchange.spot.symbol           tradable markets and their rule sets
//   com.exchange.spot.order            orders and their lifecycle
//   com.exchange.spot.trade            executions -- the tape
//   com.exchange.spot.limit.exchange   exchange-wide order caps, the default
//   com.exchange.spot.limit.override   per-user override of the exchange-wide caps
//   com.exchange.spot.limit.symbol     per-user-per-symbol override of a symbol's caps
//
// One package per table, same rule as com.exchange.user -- limit's three tables get three
// packages rather than one, even though they are all "about limits."
//
// Order and trade ids are ULIDs assigned by the application, not by the database: the API
// returns an id in its 202 response before anything is written, and the engine references
// that same id while matching in RAM.
//
// No dependency on user -- user_id is a plain column. OrderService (com.exchange.spot.order)
// is the one exception to "no dependency on wallet or fee": it locks funds via
// BalanceService (wallet) and resolves a rate from the fee tables at acceptance, then
// stamps it onto the order -- fills still never look fees up again, only placement does.
// Lives here rather than in an app because both web-bff and spot-open-api need it, the
// same "does more than one app need it" test ApiKeyService (libs/domain/user) was placed
// by -- see AGENTS.md.

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

    implementation(project(":libs:domain:wallet"))   // BalanceService, for locking
    implementation(project(":libs:domain:fee"))      // the fee tables, resolved at acceptance
    implementation(project(":libs:domain:marketdata")) // PriceGrouping, for the catalog's depth selector; WsMessage
    implementation("com.fasterxml.jackson.core:jackson-annotations") // @JsonInclude on the stream frames
    implementation(project(":libs:contracts"))       // Command/PlaceOrder, to publish
    implementation(libs.spring.kafka)                // KafkaTemplate, to publish

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Mockito, for OrderServiceTest -- same starter web-bff/wallet already use.
    testImplementation(libs.spring.boot.starter.test)
}

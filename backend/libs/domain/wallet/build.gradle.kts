// The wallet domain: what can be held, on which chains, who holds it, and every movement.
//
//   com.exchange.wallet.asset       assets -- BTC, USDT, USD
//   com.exchange.wallet.network     chains, and each asset's per-chain configuration
//   com.exchange.wallet.balance     available/locked per user per asset
//   com.exchange.wallet.ledger      append-only record of every movement
//   com.exchange.wallet.address     per-user deposit addresses
//   com.exchange.wallet.deposit     inbound transfers, credited to a balance
//   com.exchange.wallet.withdrawal  outbound transfers, debited from a balance
//   com.exchange.wallet.allowlist   withdrawal addresses a user has pre-approved
//
// No dependency on libs/domain/user. Balances carry a plain user_id with no foreign key,
// so this module can move to its own database without untangling anything.

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
    implementation(project(":libs:common")) // Ulid, for LedgerEntry's app-generated id
    implementation(project(":libs:contracts")) // Settlement/BalanceChanged, to publish
    implementation(libs.spring.boot.starter.data.jpa)
    // KafkaTemplate, to announce a balance change after it commits. The same arrangement
    // libs/domain/spot already has for publishing commands -- see BalanceService for why the
    // producer lives in the writer rather than in each app that calls it.
    implementation(libs.spring.kafka)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Mockito, via the same starter web-bff already uses -- BalanceService's tests mock
    // BalanceRepository/LedgerEntryRepository rather than standing up a real database.
    testImplementation(libs.spring.boot.starter.test)
}

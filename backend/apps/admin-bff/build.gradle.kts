plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:core"))
    implementation(project(":libs:persistence"))
    implementation(project(":libs:domain:admin"))
    // The treasury screen: balances and the ledger sums behind them, the user rows the
    // exchange's own accounts are, and the two moves staff make with the house's money
    // (docs/reference/treasury.md). The fee sweep itself runs in spot-settlement.
    implementation(project(":libs:domain:wallet"))
    implementation(project(":libs:domain:user"))
    // The dashboard's trade volume, and later the orders screen. Brings Mongo with it through
    // market data, which this app never talks to -- see AdminBffApplication's exclusions.
    implementation(project(":libs:domain:spot"))
    implementation(project(":libs:domain:fee"))
    // Who leads: the applications staff decide. By class, not by scanning com.exchange.copy --
    // the rest of that module places orders, and this app has no order path.
    implementation(project(":libs:domain:copy"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    // BCryptPasswordEncoder for AdminAuthService and the CLI.
    implementation(libs.spring.security.crypto)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
}

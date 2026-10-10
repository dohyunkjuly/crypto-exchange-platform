plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":libs:common"))               // Topics, Money
    implementation(project(":libs:contracts"))            // Event/TradeExecuted
    implementation(project(":libs:domain:marketdata"))    // Candle + its repository
    implementation(libs.spring.kafka)

    // Deliberately NOT libs:persistence. This app never touches the `exchange` MySQL
    // database -- it consumes the same events spot-settlement does, but writes a derived
    // read model to MongoDB instead. Keeping the JDBC/Flyway dependencies out means a
    // MySQL outage cannot stop candles from being built, and this app can never
    // accidentally grow a write to an order or a balance.

    // starter-web + starter-actuator together, for the same reason and with the same
    // justification as spot-settlement's: a liveness probe's side door, not a second
    // public interface. See health.ConsumerLivenessHealthIndicator.
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.web)

    testImplementation(libs.spring.boot.starter.test)
}

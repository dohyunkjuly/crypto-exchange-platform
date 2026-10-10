// Copy trading: who leads, who follows whom, and what each copy did
// (docs/reference/copy-trading.md).
//
//   com.exchange.copy            what the order path asks of a lead or a copy account
//   com.exchange.copy.settings   what staff set for the whole exchange, one row
//   com.exchange.copy.marketcap  a market's own slippage cap
//   com.exchange.copy.leader     an application to lead and its outcome
//   com.exchange.copy.follow     one follower copying one leader
//   com.exchange.copy.leadorder  a lead account's order and the fraction it used
//   com.exchange.copy.order      one order placed, or not, in a copy account
//   com.exchange.copy.position   what a copy account holds and what it cost
//
// The last domain in the chain: user <- wallet <- spot <- copy. Every id that points into
// another domain is a plain column with no foreign key.

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
    implementation(project(":libs:domain:user"))   // what a sub-account is for
    implementation(project(":libs:domain:wallet")) // the balance a lead order draws on
    implementation(project(":libs:domain:spot"))   // Side, and the order path's hook
    implementation(libs.spring.boot.starter.data.jpa)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.spring.boot.starter.test)
}

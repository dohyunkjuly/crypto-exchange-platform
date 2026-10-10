// The user domain: identity, sessions, and the auth audit log.
//
// One package per table: the entity, its repository, and its enums together.
//
//   com.exchange.user           the user itself
//          .user.session        live sessions
//          .user.audit          append-only auth event log
//          .user.apikey         programmatic trading credentials
//          .user.mfa            enrolled second factors
//          .user.kyc            identity verification
//          .user.fee            negotiated fee overrides
//
// Nothing imports across those packages, which is the sign the split follows a real seam
// rather than being filing for its own sake.
//
// ApiKeyService lives in .user.apikey, not in whichever app calls it, because both
// web-bff and open-api need it (open-api's own doc comment plans /v1/account for
// programmatic key management too, and will need to verify a signed request's HMAC
// against a stored key besides). Unlike AuthService, which lives in web-bff alone since
// open-api has no login flow to share it with. ApiKeyService throws plain, code-less
// exceptions; each app's own exception handling maps those to that app's own ErrorCode
// (BffHttpErrorCode, OpenApiErrorCode) rather than this module depending on either app.
//
// One domain per module. wallet, spot and futures get their own beside this one, and the
// module boundary is what makes extracting one into its own service later a move rather
// than an untangling.

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
    // AesGcmCipher, for encrypting user_api_keys.secret_encrypted.
    implementation(project(":libs:common"))

    // implementation, not api: ApiKeyService now fronts the repositories, so JPA is this
    // module's own implementation detail rather than something a consumer needs on its
    // own compile classpath.
    implementation(libs.spring.boot.starter.data.jpa)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Mockito, for MfaServiceTest -- same starter domain/spot and web-bff already use.
    testImplementation(libs.spring.boot.starter.test)
}

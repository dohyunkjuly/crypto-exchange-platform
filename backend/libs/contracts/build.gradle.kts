// This module has no hand-written Java. Its whole job is to compile the .proto
// files in src/main/proto into Java classes, so every service in the repo speaks
// the same message types.
//
// src/main/proto is Gradle's default location for protobuf, so there is no srcDir
// configuration here -- the plugin already knows where to look.

plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

dependencies {
    // `api`, not `implementation`: consumers need protobuf-java on their own compile
    // classpath in order to use the generated classes.
    api(libs.protobuf.java)
}

protobuf {
    protoc {
        // protoc is downloaded from Maven Central as a binary -- you do NOT need it
        // installed on your machine.
        artifact = libs.protobuf.protoc.get().toString()
    }
}

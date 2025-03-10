/*
*    Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
*
*    Licensed under the Apache License, Version 2.0 (the "License").
*    You may not use this file except in compliance with the License.
*    You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
*    Unless required by applicable law or agreed to in writing, software
*    distributed under the License is distributed on an "AS IS" BASIS,
*    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*    See the License for the specific language governing permissions and
*    limitations under the License.
*/

import org.gradle.api.tasks.testing.logging.TestExceptionFormat.*
import org.gradle.api.tasks.testing.logging.TestLogEvent.*

plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.platform:junit-platform-commons:1.8.2")
    testImplementation("org.junit.platform:junit-platform-engine:1.8.2")
    testImplementation("org.junit.platform:junit-platform-launcher:1.8.2")
    testImplementation("org.junit.platform:junit-platform-suite-engine:1.8.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.8.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")

    testImplementation("org.apache.commons:commons-dbcp2:2.8.0")
    testImplementation("org.postgresql:postgresql:42.5.0")
    testImplementation("mysql:mysql-connector-java:8.0.30")
    testImplementation("org.mariadb.jdbc:mariadb-java-client:3.1.0")
    testImplementation("com.zaxxer:HikariCP:4.+") // version 4.+ is compatible with Java 8
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc:2.7.+")
    testImplementation("org.mockito:mockito-inline:4.8.0")
    testImplementation("software.amazon.awssdk:rds:2.20.49")
    testImplementation("software.amazon.awssdk:ec2:2.20.49")
    testImplementation("org.testcontainers:testcontainers:1.17.+")
    testImplementation("org.testcontainers:mysql:1.17.+")
    testImplementation("org.testcontainers:postgresql:1.17.+")
    testImplementation("org.testcontainers:mariadb:1.17.+")
    testImplementation("org.testcontainers:junit-jupiter:1.17.+")
    testImplementation("org.testcontainers:toxiproxy:1.17.+")
    testImplementation("org.apache.poi:poi-ooxml:5.2.2")
    testImplementation("org.slf4j:slf4j-simple:1.7.+")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.13.4")
}

tasks.withType<Test> {

    testClassesDirs += fileTree("./libs") { include("*.jar") } + project.files("./test")
    classpath += fileTree("./libs") { include("*.jar") } + project.files("./test")
    outputs.upToDateWhen { false }

    useJUnitPlatform()

    testLogging {
        events(PASSED, FAILED, SKIPPED)
        showStandardStreams = true
        exceptionFormat = FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }

    systemProperty("java.util.logging.config.file", "./test/resources/logging-test.properties")
    systemProperty("junit.jupiter.params.displayname.default", "{displayName} - {arguments}")

    reports.junitXml.required.set(true)
    reports.junitXml.outputLocation.set(file("${project.buildDir}/test-results/container-" + System.currentTimeMillis()))

    reports.html.required.set(false)
}

tasks.register<Test>("in-container") {
    filter.excludeTestsMatching("software.*") // exclude unit tests
    filter.excludeTestsMatching("integration.container.*") // exclude old integration tests

    // modify below filter to select specific integration tests
    // see https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/testing/TestFilter.html
    filter.includeTestsMatching("integration.refactored.container.tests.*")
}

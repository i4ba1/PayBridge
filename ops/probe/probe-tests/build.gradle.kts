plugins {
    `java-library`
    jacoco
}

jacoco { toolVersion = libs.versions.jacoco.get() }

dependencies {
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.dgs.bom))
    testImplementation(platform(libs.testcontainers.bom))

    testImplementation(project(":probe-core"))
    testImplementation(project(":probe-client"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-amqp")
    testImplementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.session:spring-session-data-redis")
    testImplementation("com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-mongodb")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val coreClasses = project(":probe-core").layout.buildDirectory.dir("classes/java/main")

tasks.test {
    useJUnitPlatform { excludeTags("integration") }
    finalizedBy(tasks.jacocoTestReport)
}

val integrationTest by tasks.registering(Test::class) {
    description = "Containers-backed probes: DB, broker, Mongo, Redis, DGS and Apollo."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
    // Testcontainers 2.x needs the Docker named pipe/socket on Windows; pass env through.
    environment(System.getenv().filterKeys { it.startsWith("DOCKER_") || it == "TESTCONTAINERS_HOST_OVERRIDE" })
}

tasks.jacocoTestReport {
    dependsOn(tasks.test, integrationTest)
    executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") })
    classDirectories.setFrom(files(coreClasses))
    sourceDirectories.setFrom(files(project(":probe-core").file("src/main/java")))
    reports { xml.required = true; html.required = true }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test, integrationTest)
    executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") })
    classDirectories.setFrom(files(coreClasses))
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.50".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.00".toBigDecimal() }
        }
    }
}

tasks.check {
    dependsOn(integrationTest, tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}

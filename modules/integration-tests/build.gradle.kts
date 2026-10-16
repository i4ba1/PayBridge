plugins {
    id("paybridge.integration-test")
}

dependencies {
    testImplementation(project(":domain"))
    testImplementation(project(":contracts"))
    testImplementation(project(":application"))
    testImplementation(project(":adapter-mysql"))
    testImplementation(project(":adapter-mongo"))
    testImplementation(project(":adapter-rabbit"))
    testImplementation(project(":adapter-provider"))
    testImplementation(project(":core-app"))
    testImplementation(project(":apollo-client"))
    testImplementation(project(":console-app"))
    testImplementation(project(":simulator-app"))
    testImplementation(libs.archunit.junit5)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-crypto")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
}

// Testcontainers 2.x finds Docker through DOCKER_* variables on Windows; pass them through to the test JVM.
tasks.withType<Test>().configureEach {
    environment(System.getenv().filterKeys { it.startsWith("DOCKER_") || it == "TESTCONTAINERS_HOST_OVERRIDE" })
}

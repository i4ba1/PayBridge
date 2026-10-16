plugins {
    `java-library`
    jacoco
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "com.paybridge"
version = "0.1.0-SNAPSHOT"

extensions.configure<JavaPluginExtension> {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all"))
    options.encoding = "UTF-8"
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencyLocking { lockAllConfigurations() }

dependencies {
    testImplementation(platform(libs.findLibrary("spring-boot-bom").get()))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jacoco { toolVersion = libs.findVersion("jacoco").get().requiredVersion }

tasks.named<Test>("test") {
    // Container, contract and failure tests run in :integration-tests under their own tasks.
    useJUnitPlatform { excludeTags("integration", "contract", "failure") }
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required = true
        html.required = true
    }
}

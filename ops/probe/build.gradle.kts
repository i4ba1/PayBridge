subprojects {
    apply(plugin = "java")
    group = "com.paybridge.probe"
    version = "0.0.1"

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
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

subprojects {
    dependencyLocking { lockAllConfigurations() }
}

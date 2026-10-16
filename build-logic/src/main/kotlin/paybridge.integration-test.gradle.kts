plugins {
    id("paybridge.java-library")
}

val testSourceSet = extensions.getByType<SourceSetContainer>().getByName("test")

fun tagged(taskName: String, tag: String) = tasks.register<Test>(taskName) {
    description = "Runs tests tagged '$tag'."
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform { includeTags(tag) }
    shouldRunAfter(tasks.named("test"))
}

val integrationTest = tagged("integrationTest", "integration")
val contractTest = tagged("contractTest", "contract")
val failureTest = tagged("failureTest", "failure")

tasks.named("check") { dependsOn(integrationTest, contractTest, failureTest) }

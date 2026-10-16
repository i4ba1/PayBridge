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
}

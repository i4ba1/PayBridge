plugins {
    id("paybridge.boot-app")
}

dependencies {
    implementation(project(":apollo-client"))
}

tasks.register("assembleConsole") {
    description = "Stable alias: builds the console boot jar."
    group = "build"
    dependsOn(tasks.named("bootJar"))
}

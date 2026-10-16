pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "paybridge"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

listOf(
    "contracts", "domain", "application",
    "adapter-mysql", "adapter-mongo", "adapter-rabbit", "adapter-provider",
    "core-app", "apollo-client", "console-app", "simulator-app", "integration-tests",
).forEach { name ->
    include(name)
    project(":$name").projectDir = file("modules/$name")
}

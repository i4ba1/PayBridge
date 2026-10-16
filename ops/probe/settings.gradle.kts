rootProject.name = "paybridge-probe"

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

include("probe-core", "probe-client", "probe-tests")

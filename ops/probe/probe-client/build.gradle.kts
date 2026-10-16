plugins {
    `java-library`
    alias(libs.plugins.apollo)
}

dependencies {
    api(libs.apollo.runtime.java)
}

apollo {
    service("probe") {
        packageName.set("com.paybridge.probe.client.gen")
        generateKotlinModels.set(false)
        // The same .graphqls file the DGS server loads.
        schemaFiles.from(rootProject.file("probe-core/src/main/resources/schema/probe.graphqls"))
    }
}

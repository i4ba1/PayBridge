plugins {
    id("paybridge.java-library")
    alias(libs.plugins.apollo)
}

dependencies {
    api(libs.apollo.runtime.java)
    api(project(":application"))
    api(project(":contracts"))
}

apollo {
    service("paybridge") {
        packageName.set("com.paybridge.apollo.gen")
        generateKotlinModels.set(false)
        // Single source of truth: the same file the DGS server in :core-app loads from :contracts.
        schemaFiles.from(rootProject.file("modules/contracts/src/main/resources/graphql/paybridge.graphqls"))
    }
}

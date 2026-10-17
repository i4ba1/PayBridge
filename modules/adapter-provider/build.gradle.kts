plugins {
    id("paybridge.java-library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    api(project(":application"))
    api(project(":contracts"))
    implementation(project(":domain"))
    implementation(platform(libs.findLibrary("spring-boot-bom").get()))
    implementation("com.fasterxml.jackson.core:jackson-databind")
}

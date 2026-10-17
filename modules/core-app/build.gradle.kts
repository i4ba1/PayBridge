plugins {
    id("paybridge.boot-app")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(platform(libs.findLibrary("dgs-bom").get()))
    implementation(project(":domain"))
    implementation(project(":contracts"))
    implementation(project(":application"))
    implementation(project(":adapter-mysql"))
    implementation(project(":adapter-mongo"))
    implementation(project(":adapter-rabbit"))
    implementation(project(":adapter-provider"))

    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter")
}

plugins {
    id("paybridge.boot-app")
}

dependencies {
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
}

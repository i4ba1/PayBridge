plugins {
    id("paybridge.java-library")
}

dependencies {
    api(project(":application"))
    api(project(":domain"))
}

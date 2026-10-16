plugins {
    id("paybridge.java-library")
}

dependencies {
    api(project(":domain"))
    api(project(":contracts"))
}

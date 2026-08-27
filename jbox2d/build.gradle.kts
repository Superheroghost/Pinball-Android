plugins {
    `java-library`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

val APP_VERSION_NAME: String by project

group = "com.superheroghost.neonpinball"
version = APP_VERSION_NAME

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)           // compile Kotlin to plain JVM bytecode — no Android SDK needed
    alias(libs.plugins.kotlin.serialization) // enables @Serializable code generation at compile time
}

kotlin {
    jvmToolchain(17) // output class files target JVM 17
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    // junit-platform-launcher is the entry point Gradle uses to discover JUnit 5 tests
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform() // tells Gradle's test task to use the JUnit 5 runner
}

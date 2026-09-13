plugins {
    kotlin("jvm") version "2.4.20"
    application
}

group = "ai.project.rio"
version = "0.1.0"

val ktorVersion = "3.5.2"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    // JSON for HTTP bodies (main) and JSON Schema validation of responses (tests).
    implementation("com.alibaba.fastjson2:fastjson2:2.0.65")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("ch.qos.logback:logback-classic:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}

// Toolchain JDK. Local builds pin 25; CI overrides via ORG_GRADLE_PROJECT_jdkVersion to
// also cover the older LTS releases (21, 17).
val jdkVersion = findProperty("jdkVersion")?.toString()?.let { raw ->
    raw.toIntOrNull() ?: throw GradleException("jdkVersion must be a JDK major version such as 25, got '$raw'")
} ?: 25

kotlin {
    jvmToolchain(jdkVersion)
}

// sqlite-jdbc loads a native library; JDK 24+ warns (and will eventually refuse) without this flag.
val nativeAccess = "--enable-native-access=ALL-UNNAMED"

application {
    mainClass.set("ai.project.rio.ApplicationKt")
    applicationDefaultJvmArgs = listOf(nativeAccess)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(nativeAccess)
    // Tests validate HTTP responses against the shared schemas in ../contracts/schemas.
    // They are read in place; nothing is copied into backend resources.
    systemProperty("contracts.schemas.dir", projectDir.resolve("../contracts/schemas").canonicalPath)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

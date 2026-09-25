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
    // JSON for HTTP bodies.
    implementation("com.alibaba.fastjson2:fastjson2:2.0.65")
    // PostgreSQL JDBC driver; db/Database.kt also validates DB_URL with its URL parser.
    implementation("org.postgresql:postgresql:42.7.13")
    // Connection pool behind the PostgreSQL JdbcTemplate built in db/Database.kt.
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("ch.qos.logback:logback-classic:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    // Draft 2020-12 validation of HTTP bodies against ../contracts/schemas. joni backs networknt's
    // ECMAScript regex factory, so `pattern` reads as Ajv does for the constructs the contracts use;
    // it is not a full ECMA-262 engine, and JsonSchemaAssertions refuses the known divergence (#68).
    testImplementation("com.networknt:json-schema-validator:3.0.7")
    testImplementation("org.jruby.joni:joni:2.2.6")
    // Starts the postgres:17 container behind db/PostgresTestDatabase.kt; needs a Docker daemon.
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
}

// Toolchain JDK. Local builds pin 25; CI overrides via ORG_GRADLE_PROJECT_jdkVersion to
// also cover the older LTS releases (21, 17).
val jdkVersion = findProperty("jdkVersion")?.toString()?.let { raw ->
    raw.toIntOrNull() ?: throw GradleException("jdkVersion must be a JDK major version such as 25, got '$raw'")
} ?: 25

kotlin {
    jvmToolchain(jdkVersion)
}

// Ktor's CallLogging colors its lines with jansi, which loads a native library on the first logged call;
// JDK 24+ warns (and will eventually refuse) without this flag.
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

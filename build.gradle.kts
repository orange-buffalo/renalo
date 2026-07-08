import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.register

plugins {
    id("io.micronaut.application") version "5.0.0"
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.kotlin.kapt") version "2.4.0"
    id("com.google.cloud.tools.jib") version "3.5.3"
}

group = "io.orange-buffalo"
version = "0.1.0-SNAPSHOT"

val javaVersion = 25

micronaut {
    version("5.0.2")
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("io.orangebuffalo.renalo.*")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

kotlin {
    jvmToolchain(javaVersion)
}

application {
    mainClass.set("io.orangebuffalo.renalo.ApplicationKt")
}

dependencies {
    kapt("io.micronaut:micronaut-inject-java")
    kapt("io.micronaut.data:micronaut-data-processor")

    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut.data:micronaut-data-jdbc")
    implementation("io.micronaut.flyway:micronaut-flyway")
    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
    implementation("io.micronaut.security:micronaut-security")
    implementation("io.micronaut.security:micronaut-security-jwt")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    implementation("jakarta.annotation:jakarta.annotation-api")
    implementation("com.yubico:webauthn-server-core:2.7.0")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.yaml:snakeyaml")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform("org.junit:junit-bom:6.1.0"))
    testImplementation("com.microsoft.playwright:playwright:1.60.0")
    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.9.1")
    testImplementation("io.kotest:kotest-assertions-json-jvm:5.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val uiDir = layout.projectDirectory.dir("ui")
val uiBuildDir = uiDir.dir("dist")
val generatedUiResourcesDir = layout.buildDirectory.dir("generated/resources/ui")
val staticUiDir = generatedUiResourcesDir.map { it.dir("public") }

val uiInstall by tasks.registering(Exec::class) {
    workingDir(uiDir.asFile)
    commandLine("bun", "install", "--frozen-lockfile")
    inputs.file(uiDir.file("bun.lock"))
    inputs.file(uiDir.file("package.json"))
}

val uiCheck by tasks.registering(Exec::class) {
    dependsOn(uiInstall)
    workingDir(uiDir.asFile)
    commandLine("bun", "run", "check")
    inputs.dir(uiDir.dir("src"))
    inputs.file(uiDir.file("biome.json"))
    inputs.file(uiDir.file("tsconfig.json"))
}

val uiBuild by tasks.registering(Exec::class) {
    dependsOn(uiInstall)
    workingDir(uiDir.asFile)
    commandLine("bun", "run", "build")
    inputs.dir(uiDir.dir("src"))
    inputs.dir(uiDir.dir("scripts"))
    outputs.dir(uiBuildDir)
}

val copyUi by tasks.registering(Copy::class) {
    dependsOn(uiBuild)
    from(uiBuildDir)
    into(staticUiDir)
}

val playwrightInstall by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Installs the Chromium headless shell used by Playwright Java tests."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "--only-shell", "chromium")
}

sourceSets {
    main {
        resources.srcDir(generatedUiResourcesDir)
    }
}

tasks.named("processResources") {
    dependsOn(copyUi)
}

tasks.matching { it.name == "inspectRuntimeClasspath" }.configureEach {
    dependsOn(copyUi)
}

tasks.named("check") {
    dependsOn(uiCheck)
}

tasks.withType<Test>().configureEach {
    dependsOn(playwrightInstall)
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

jib {
    from {
        image = "eclipse-temurin:25-jre"
    }
    to {
        image = "ghcr.io/orange-buffalo/renalo:latest"
    }
    container {
        ports = listOf("8080")
        mainClass = "io.orangebuffalo.renalo.ApplicationKt"
        creationTime = "USE_CURRENT_TIMESTAMP"
        environment = mapOf("MICRONAUT_ENVIRONMENTS" to "prod")
    }
}

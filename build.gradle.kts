import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.register

plugins {
    id("io.micronaut.application") version "5.0.2"
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.kotlin.kapt") version "2.4.10"
    id("com.google.cloud.tools.jib") version "3.5.4"
    id("com.github.jmongard.git-semver-plugin") version "0.19.2"
    id("com.github.ben-manes.versions") version "0.54.0"
    id("org.gradle.test-retry") version "1.6.5"
}

group = "io.orange-buffalo"

semver {
    createReleaseTag = true
    releaseTagNameFormat = "v%s"
}
val ver = semver.version
allprojects {
    version = ver
}

val javaVersion = 25

micronaut {
    version("5.0.5")
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
    implementation("com.yubico:webauthn-server-core:2.9.0")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.yaml:snakeyaml")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("com.microsoft.playwright:playwright:1.61.0")
    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("io.kotest:kotest-assertions-core-jvm:6.2.2")
    testImplementation("io.kotest:kotest-assertions-json-jvm:6.2.2")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val uiDir = layout.projectDirectory.dir("ui")
val uiBuildDir = uiDir.dir("dist")
val generatedUiResourcesDir = layout.buildDirectory.dir("generated/resources/ui")
val staticUiDir = generatedUiResourcesDir.map { it.dir("public") }

val uiInstall = tasks.register<Exec>("uiInstall") {
    workingDir(uiDir.asFile)
    commandLine("bun", "install", "--frozen-lockfile")
    inputs.file(uiDir.file("bun.lock"))
    inputs.file(uiDir.file("package.json"))
}

val uiCheck = tasks.register<Exec>("uiCheck") {
    dependsOn(uiInstall)
    workingDir(uiDir.asFile)
    commandLine("bun", "run", "check")
    inputs.dir(uiDir.dir("src"))
    inputs.file(uiDir.file("biome.json"))
    inputs.file(uiDir.file("tsconfig.json"))
}

val uiTest = tasks.register<Exec>("uiTest") {
    dependsOn(uiInstall)
    workingDir(uiDir.asFile)
    commandLine("bun", "run", "test")
    inputs.dir(uiDir.dir("src"))
}

val uiBuild = tasks.register<Exec>("uiBuild") {
    dependsOn(uiInstall)
    workingDir(uiDir.asFile)
    commandLine("bun", "run", "build")
    inputs.dir(uiDir.dir("src"))
    inputs.dir(uiDir.dir("scripts"))
    outputs.dir(uiBuildDir)
}

val copyUi = tasks.register<Copy>("copyUi") {
    dependsOn(uiBuild)
    from(uiBuildDir)
    into(staticUiDir)
}

val playwrightInstall = tasks.register<JavaExec>("playwrightInstall") {
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
    dependsOn(uiCheck, uiTest)
}

tasks.named("dependencyUpdates") {
    notCompatibleWithConfigurationCache("The dependency updates plugin does not support the configuration cache")
}

val documentationScreenshotsEnabled = providers.environmentVariable("RENALO_DOCUMENTATION_SCREENSHOTS").orElse("false")

tasks.withType<Test>().configureEach {
    dependsOn(playwrightInstall)
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    environment("RENALO_DOCUMENTATION_SCREENSHOTS", documentationScreenshotsEnabled.get())
    inputs.property("documentationScreenshotsEnabled", documentationScreenshotsEnabled)
    useJUnitPlatform()
    retry {
        maxRetries.set(3)
        failOnPassedAfterRetry.set(false)
    }
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

jib {
    from {
        image = "eclipse-temurin:25-jre"
    }
    to {
        image = "ghcr.io/orange-buffalo/renalo:${project.version}"
    }
    container {
        ports = listOf("8080")
        mainClass = "io.orangebuffalo.renalo.ApplicationKt"
        creationTime = "USE_CURRENT_TIMESTAMP"
        environment = mapOf("MICRONAUT_ENVIRONMENTS" to "prod")
    }
}

// Root build: shared conventions for every Java module.
plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "io.github.hemanthvk980"
    version = "0.1.0"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing", "-parameters"))
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        finalizedBy("jacocoTestReport")
    }

    // Coverage gate (line coverage). `check` fails below the threshold.
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn("test")
        violationRules { rule { limit { counter = "LINE"; minimum = "0.60".toBigDecimal() } } }
    }
    tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }
}

// The orchestrator deliberately has ZERO runtime dependencies (JDK 21 only):
// small attack surface, trivially auditable, runs anywhere `java` runs.
plugins {
    application
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("io.github.hemanthvk980.orchestrator.cli.OrchestratorCli")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in` // console approvals
}

// Code that talks to the network (Gemini) or a terminal (console approvals) is exercised manually /
// in live runs, not in unit tests; keep it out of the coverage ratio rather than faking it.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    classDirectories.setFrom(files(classDirectories.files.map {
        fileTree(it) { exclude("**/GeminiClient*", "**/ConsoleHumanGateway*") }
    }))
}

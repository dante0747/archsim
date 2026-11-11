// The ArchSim MCP server (stdio) on the official MCP Java SDK: the engine as tools for AI coding
// agents, as the Python server in packages/mcp had them (ADR-0058). See README.md.
plugins {
    id("archsim.java-conventions")
    application
}

dependencies {
    implementation(project(":archsim-engine"))
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson3)
    // the SDK logs through SLF4J; on stdio its lines would reach no one, and a provider that
    // printed to standard output would break the protocol: the no-op one (ADR-0058)
    runtimeOnly(libs.slf4j.nop)

    // the session recorded of the Python server is compared as the golden files and the
    // simulator's recorded runs are
    testImplementation(testFixtures(project(":archsim-engine")))
    testImplementation(testFixtures(project(":archsim-model")))
}

application {
    mainClass = "io.archsim.mcp.ArchsimMcp"
    applicationName = "archsim-mcp"
}

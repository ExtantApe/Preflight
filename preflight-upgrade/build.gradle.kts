// preflight-upgrade: a command-line tool that checks a server's plugins against a new Paper version
// on a throwaway copy, then switches off what won't start, with a yes and a restore point.
// No dependencies, so the jar runs on its own with Java 25.

dependencies {
    "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

val pluginJar = project(":preflight-plugin").tasks.named<Jar>("jar")

tasks.named<ProcessResources>("processResources") {
    // The check drops this exact Preflight into the test copy, so the report format always matches.
    from(pluginJar) {
        into("bundled")
        rename { "Preflight.jar" }
    }
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("preflight-upgrade.properties") { expand(props) }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("preflight-upgrade")
    manifest {
        attributes("Main-Class" to "com.extantape.preflight.upgrade.Main")
    }
}

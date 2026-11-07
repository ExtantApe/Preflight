dependencies {
    // compileOnly: the classes come from the Preflight plugin at runtime, if it's installed.
    "compileOnly"(project(":preflight-api"))
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("PreflightExample")
}

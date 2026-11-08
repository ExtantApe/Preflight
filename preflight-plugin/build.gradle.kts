val paperApi = "io.papermc.paper:paper-api:26.1.2.build.74-stable"

dependencies {
    "implementation"(project(":preflight-api"))

    "testImplementation"(paperApi)
    "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("Preflight")

    // The API is bundled un-relocated: other plugins link against these exact class names.
    val runtimeClasspath = configurations.named("runtimeClasspath")
    dependsOn(runtimeClasspath)
    from(runtimeClasspath.map { files -> files.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "LICENSE")
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

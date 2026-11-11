// Test-only plugins for test/live/upgrade.mjs, the preflight-upgrade live checks. Never released.
// One small class, packed into four jars that each fail differently. Each jar's descriptor is in src/jars/<Name>.
val fixtures = listOf("PfBadApi", "PfNoMain", "PfNeedsBad", "PfChainEnd")
val main = the<SourceSetContainer>()["main"]

tasks.named<Jar>("jar") { enabled = false }

fixtures.forEach { name ->
    val jar = tasks.register<Jar>("jar$name") {
        archiveFileName.set("$name.jar")
        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        from(main.output.classesDirs)
        from("src/jars/$name")
    }
    tasks.named("assemble") { dependsOn(jar) }
}

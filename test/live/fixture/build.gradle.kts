dependencies { "compileOnly"(project(":preflight-api")) }
tasks.named<Jar>("jar") { archiveBaseName.set("PfFixture") }

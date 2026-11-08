val paperApi = "io.papermc.paper:paper-api:26.1.2.build.74-stable"

subprojects {
    apply(plugin = "java")

    group = "com.extantape"
    version = "0.1.0"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    dependencies {
        "compileOnly"(paperApi)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    tasks.withType<ProcessResources>().configureEach {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") { expand(props) }
    }

    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE"))
    }
}

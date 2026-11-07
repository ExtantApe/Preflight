val spigotApi = "org.spigotmc:spigot-api:1.21.11-R0.2-SNAPSHOT"

subprojects {
    apply(plugin = "java")

    group = "com.extantape"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/groups/public/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    dependencies {
        "compileOnly"(spigotApi)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<ProcessResources>().configureEach {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }

    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE"))
    }
}

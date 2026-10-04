plugins {
    id("java-library")
    id("maven-publish")

    id("xyz.jpenilla.run-paper")
    id("com.gradleup.shadow")
}

runPaper.folia.registerTask()

allprojects {
    group = "com.fancyinnovations"
    version = getFNMVersion()
    description = "Addon for FancyNpcs that adds support for custom models"
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    compileOnly(project(":plugins:fancynpcs-v2:fn-v2-api"))
    // Provided by Paper; never shade a second copy of its networking classes.
    compileOnly("io.netty:netty-transport:4.2.7.Final")
    compileOnly(files(rootProject.file(providers.gradleProperty("betterModelJar").getOrElse("deps/bettermodel-3.5.0-paper.jar"))))
    compileOnly(files(rootProject.file(providers.gradleProperty("modelEngineJar").getOrElse("deps/ModelEngine-R4.1.1.jar"))))

    implementation(project(":libraries:common"))
    implementation(project(":libraries:jdb"))
    implementation(project(":libraries:config"))
    implementation("de.oliver.FancyAnalytics:java-sdk:0.0.6")
    implementation("de.oliver.FancyAnalytics:mc-api:0.1.14")
    implementation("de.oliver.FancyAnalytics:logger:0.0.10")

    compileOnly("org.incendo:cloud-core:2.1.0")
    compileOnly("org.incendo:cloud-paper:2.0.1")
    compileOnly("org.incendo:cloud-annotations:2.1.0")
    annotationProcessor("org.incendo:cloud-annotations:2.1.0")

    implementation("org.jetbrains:annotations:26.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(project(":plugins:fancynpcs-v2:fn-v2-api"))
    testImplementation("io.netty:netty-transport:4.2.7.Final")
    testImplementation(files(rootProject.file(providers.gradleProperty("betterModelJar").getOrElse("deps/bettermodel-3.5.0-paper.jar"))))
    testImplementation(files(rootProject.file(providers.gradleProperty("modelEngineJar").getOrElse("deps/ModelEngine-R4.1.1.jar"))))
}

tasks {
    test {
        useJUnitPlatform()
        dependsOn(":plugins:fancynpcs-v2:fn-v2-api:shadowJar")
        val asciiClasspathDir = providers.gradleProperty("testClasspathDir")
        doFirst {
            asciiClasspathDir.orNull?.let { directory ->
                val staged = classpath.files.filter { it.exists() }.mapIndexed { index, entry ->
                    val target = file(directory).resolve("entry-$index")
                    if (entry.isDirectory) {
                        entry.copyRecursively(target, overwrite = true)
                        target
                    } else {
                        target.mkdirs()
                        entry.copyTo(target.resolve(entry.name), overwrite = true)
                    }
                }
                classpath = files(staged)
            }
        }
    }
    runServer {
        minecraftVersion("1.21.11")

        downloadPlugins {
            // Install the locally built FancyNpcs core for this distribution.
            modrinth("BetterModel", "8xoSUfzr") // 3.2.0
//            modrinth("FancyDialogs", "1.1.2.53")
//            modrinth("FancyHolograms", "2.9.1")
//            modrinth("FancyDialogs", "1.1.2")
//            modrinth("FancyEconomy", "1.0.3+6")

//            hangar("PlaceholderAPI", "2.11.6")
//            hangar("ViaVersion", "5.8.1")
//            hangar("ViaBackwards", "5.8.1")
        }
    }

    shadowJar {
        relocate("org.incendo", "de.oliver")
        archiveClassifier.set("")
        archiveBaseName.set("FancyNpcsModel")
    }

    compileJava {
        options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
        options.release = 25
        // For cloud-annotations, see https://cloud.incendo.org/annotations/#command-components
        options.compilerArgs.add("-parameters")
    }

    javadoc {
        options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
    }

    processResources {
        filteringCharset = Charsets.UTF_8.name() // We want UTF-8 for everything

        val props = mapOf(
            "description" to project.description,
            "version" to getFNMVersion(),
            "commit_hash" to gitCommitHash.get(),
            "channel" to (System.getenv("RELEASE_CHANNEL") ?: "").ifEmpty { "undefined" },
            "platform" to (System.getenv("RELEASE_PLATFORM") ?: "").ifEmpty { "undefined" }
        )

        inputs.properties(props)

        filesMatching("paper-plugin.yml") {
            expand(props)
        }

        filesMatching("version.yml") {
            expand(props)
        }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

// Source archives have no .git. Never inherit a surrounding repository's HEAD.
val gitCommitHash: Provider<String> = providers.gradleProperty("sourceCommitHash").orElse(
    if (rootProject.file(".git").exists()) {
        providers.exec {
            workingDir(rootProject.projectDir)
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText.map { it.trim() }
    } else {
        providers.provider { rootProject.file("SOURCE_COMMIT").readText().trim() }
    }
)

val gitCommitMessage: Provider<String> = providers.exec {
    commandLine("git", "log", "-1", "--pretty=%B")
}.standardOutput.asText.map { it.trim() }

fun getFNMVersion(): String {
    return file("VERSION").readText().trim()
}

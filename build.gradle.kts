import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val pluginGroup: String by extra
val pluginVersion: String by extra
val pluginName: String by extra
val pluginId: String by extra
val platformType: String by extra
val platformVersion: String by extra
val platformPlugins: String by extra
val pluginSinceBuild: String by extra
val pluginUntilBuild: String by extra
val pluginVerifierIdeVersions: String by extra
val publishingToken: String by extra

plugins {
    // Java support
    id("java")
    // Kotlin support
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    // gradle-intellij-plugin - read more: https://github.com/JetBrains/gradle-intellij-plugin
    id("org.jetbrains.intellij.platform") version "2.6.0"
    // gradle-changelog-plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
    id("org.jetbrains.changelog") version "2.2.1"
    // detekt linter - read more: https://detekt.github.io/detekt/gradle.html
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    // ktlint linter - read more: https://github.com/JLLeitschuh/ktlint-gradle
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

group = pluginGroup
version = pluginVersion

// Configure project's dependencies
repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}
dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.19.0")
    intellijPlatform {
        create(platformType, platformVersion)
        bundledPlugins(platformPlugins.split(',').map(String::trim).filter(String::isNotEmpty))
        pluginVerifier()
    }
    testImplementation(kotlin("test"))
}

// Configure gradle-intellij-plugin plugin.
// Read more: https://github.com/JetBrains/gradle-intellij-plugin
intellijPlatform {
    pluginConfiguration {
        id = pluginId
        name = pluginName
        version = pluginVersion
        vendor {
            name = "JetBrains"
        }
        changeNotes = provider { changelog.renderItem(changelog.getLatest(), Changelog.OutputType.HTML) }

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = File(projectDir, "README.md").readText().lines().run {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            if (!containsAll(listOf(start, end))) {
                throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
            }
            subList(indexOf(start) + 1, indexOf(end)).map {
                it.replace("](pic/", "](https://raw.githubusercontent.com/demiurg906/test-data-helper-plugin/master/pic/")
            }
        }.joinToString("\n").run { markdownToHTML(this) }

        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = pluginUntilBuild
        }
    }

    pluginVerification {
        ides {
            pluginVerifierIdeVersions
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { ideVersion ->
                    ide(IntelliJPlatformType.IntellijIdeaCommunity, ideVersion)
                }
        }
        freeArgs = listOf("-mute", "ForbiddenPluginIdPrefix") // The 'org.jetbrains' prefix is normally not allowed
    }

    publishing {
        token = publishingToken
    }
}

// Configure gradle-changelog-plugin plugin.
// Read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    version.set(pluginVersion)
    groups.set(emptyList())
}

// Configure detekt plugin.
// Read more: https://detekt.github.io/detekt/kotlindsl.html
detekt {
    config.setFrom(files("./detekt-config.yml"))
    buildUponDefaultConfig = true
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        compilerOptions.freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }

    withType<Detekt> {
        jvmTarget = "21"
        reports {
            html.required.set(false)
            xml.required.set(false)
            txt.required.set(false)
        }
    }
}

sourceSets {
    main {
        java.srcDir("src")
        resources.srcDir("resources")
    }
    test {
        java.srcDir("test")
    }
}

(tasks["runIde"] as JavaExec).apply {
    maxHeapSize = "3g"
}

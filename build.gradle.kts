import org.gradle.kotlin.dsl.withType
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val pluginVersion = extra["pluginVersion"] as String
val publishingToken = extra["publishingToken"] as String

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog") apply false
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.plugins.gradle")
        bundledPlugin("Git4Idea")
    }

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        version = pluginVersion

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description =
            File(projectDir, "README.md")
                .readText()
                .lines()
                .run {
                    val start = "<!-- Plugin description -->"
                    val end = "<!-- Plugin description end -->"

                    if (!containsAll(listOf(start, end))) {
                        throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                    }
                    subList(indexOf(start) + 1, indexOf(end)).map {
                        it.replace("](pic/", "](https://raw.githubusercontent.com/JetBrains/kotlin-compiler-devkit/master/pic/")
                    }
                }
                .joinToString("\n")
                .run { markdownToHTML(this) }

        ideaVersion {
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        freeArgs = listOf("-mute", "ForbiddenPluginIdPrefix") // The 'org.jetbrains' prefix is normally not allowed
    }

    publishing {
        token = publishingToken
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
    }

    /*
     * The bundled Vue.js plugin fails to initialize in the test runtime (its jar lives in `lib/modules`),
     * which breaks tests whenever a VFS event wakes up the TypeScript compiler listener.
     */
    prepareTestSandbox {
        disabledPlugins.add("org.jetbrains.plugins.vue")
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
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.23"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "com.yourname"
version = "0.4.1"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

intellij {
    pluginName = "android-build-booster"
    version = "2024.1"
    type = "IC"
    plugins = listOf()
}

dependencies {
    // kotlin-stdlib is provided by the IntelliJ Platform — do not add it explicitly
    // to avoid version conflicts (see https://jb.gg/intellij-platform-kotlin-stdlib)
}

tasks {
    wrapper {
        gradleVersion = "8.6"
    }

    patchPluginXml {
        sinceBuild = "241"
        pluginDescription = """
            <p>Android Build Booster helps you find and fix Android build bottlenecks directly inside IntelliJ IDEA.</p>
            <ul>
                <li><b>Health Analyzer</b>: Detects missing <code>gradle.properties</code> optimizations with one-click auto-fix</li>
                <li><b>Per-Module Build File Scanner</b>: Scans every module's <code>build.gradle(.kts)</code> for kapt vs KSP, minSdk, redundant plugins, and more</li>
                <li><b>Build Timeline</b>: Visual bar chart of per-task durations with build comparison and "Why is this slow?" detail panel</li>
                <li><b>Module Impact Analyzer</b>: Shows which modules will recompile based on VCS changes, with estimated rebuild times and stash suggestions</li>
            </ul>
        """.trimIndent()
        changeNotes = """
            <ul>
                <li><b>0.4.0</b> — Bundled abb CLI tool; auto-installs to ~/.android-build-booster/abb on first project open</li>
                <li><b>0.3.0</b> — Per-module build.gradle scanner; build history comparison in Timeline; recompile time estimation + stash suggestions in Impact tab</li>
                <li><b>0.2.0</b> — Build Timeline Dashboard + Module Impact Analyzer</li>
                <li><b>0.1.0</b> — Initial release with Gradle health analyzer and auto-fix</li>
            </ul>
        """.trimIndent()
    }

    signPlugin {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
            .orElse("")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
            .orElse("")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
            .orElse("")
    }

    publishPlugin {
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse("")
        channels = listOf("stable")
    }

    runIde {
        // JVM args for the sandbox IDE
        jvmArgs("-Xmx2g")
    }

    buildPlugin {
        // Produces build/distributions/android-build-booster-0.3.0.zip
    }

    compileKotlin {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf("-Xjvm-default=all")
        }
    }

    compileTestKotlin {
        kotlinOptions {
            jvmTarget = "17"
        }
    }
}

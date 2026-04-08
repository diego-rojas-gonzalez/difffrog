plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "com.astralcollar"
version = "1.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1")

        bundledPlugin("Git4Idea")

        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        id.set("com.astralcollar.difffrog")
        name.set("difffrog")

        description.set(
            "difffrog is an IntelliJ plugin that helps developers visualize Git changes directly " +
                    "from the toolbar. It shows live added and deleted line counts with smooth animations " +
                    "to improve awareness during development and code reviews."
        )

        vendor {
            name.set("astralcollar")
            email.set("")
            url.set("https://github.com/astralcollar/difffrog")
        }

        ideaVersion {
            sinceBuild.set("241")
            untilBuild.set(provider { null })
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
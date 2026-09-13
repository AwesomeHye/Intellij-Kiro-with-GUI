plugins {
    id("org.jetbrains.intellij.platform") version "2.5.0"
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val type = providers.gradleProperty("platformType")
        val version = providers.gradleProperty("platformVersion")
        create(type, version)
        bundledPlugin("org.jetbrains.plugins.terminal")
        instrumentationTools()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    
    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.0")
    testImplementation("io.mockk:mockk:1.13.10")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// JCEF(Chromium) 로그 비활성화 - 홈 디렉토리에 jcef_*.log 파일 생성 방지
tasks.named("runIde") {
    if (this is JavaExec) {
        jvmArgs(
            "-Djcef.log.severity=disable",
            "-Dide.browser.jcef.log.path=/dev/null",
            "-Djcef.log.file=/dev/null"
        )
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.kiro.gui"
        name = "Kiro with GUI"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "251"
        }
    }
    pluginVerification {
        ides {
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            // 2026.2 정식 릴리즈 — JCEF가 별도 플러그인으로 분리된 이후 빌드
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.PhpStorm, "2026.2.2")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.Rider, "2026.2.1", useInstaller = false)
        }
    }
}

kotlin {
    jvmToolchain(21)
}

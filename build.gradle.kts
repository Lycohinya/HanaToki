plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.paper.api)
    // stdlib 不 shade:同一台伺服器上每顆 Kotlin 插件通常都各自載入自己的 kotlin-stdlib
    // 副本;shade 進來會製造同名 class 不同 classloader 的 LinkageError 風險(見 README
    // 「Cross-plugin call safety」)。runtime 由 plugin.yml 的 Paper library loader 提供。
    compileOnly(libs.kotlin.stdlib)

    testImplementation(kotlin("test"))
    testImplementation(libs.paper.api)
}

kotlin {
    jvmToolchain(25)
}

tasks {
    build { dependsOn(shadowJar) }
    jar { archiveClassifier.set("thin") }
    shadowJar {
        archiveClassifier.set("")
    }

    runServer { minecraftVersion(libs.versions.minecraft.get()) }

    test { useJUnitPlatform() }

    processResources {
        val props = mapOf("version" to version.toString(), "description" to project.description.toString())
        // 沒有這行,改版本後 processResources 會判定 UP-TO-DATE,jar 檔名是新版但內嵌 version 是舊的
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }
}

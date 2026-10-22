plugins {
    id("java-library")
    kotlin("jvm") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
}

import com.xuncorp.spw.workshop.gradle.PluginPermission

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.spw.workshop.api)
}

spmod {
    PluginClass = "com.thirdparty.steamsync.MainPlugin"
    PluginId = "com.thirdparty.steamsync"
    PluginVersion = "1.0.0"
    PluginProvider = "由纪子Yukiko"
    PluginName = "椒盐大强兼 Parasite"
    PluginDescription = "将 QQ音乐/网易云/Spotify 等第三方播放器的播放状态同步到 Steam 丰富状态。"
    PluginPermissions = emptyList<PluginPermission>()
    PluginHasConfig = true
}

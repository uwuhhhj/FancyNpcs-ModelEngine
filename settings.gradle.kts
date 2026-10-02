pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven(url = "https://repo.papermc.io/repository/maven-public/")
        mavenLocal()
    }
}

// Use a locally installed JDK 25. The old resolver does not support this Gradle.

rootProject.name = "FancyNpcs-ModelEngine"

include(":plugins:fancynpcs-v2")
include(":plugins:fancynpcs-v2:fn-v2-api")
include(":plugins:fancynpcs-v2:implementation_26_3")
include(":plugins:fancynpcs-v2:implementation_26_2")
include(":plugins:fancynpcs-v2:implementation_26_1_2")
include(":plugins:fancynpcs-v2:implementation_1_21_11")
include(":plugins:fancynpcs-v2:implementation_1_21_9")
include(":plugins:fancynpcs-v2:implementation_1_21_6")
include(":plugins:fancynpcs-v2:implementation_1_21_5")

//include(":plugins:fancynpcs")
//include(":plugins:fancynpcs:fn-api")

include(":plugins:fancynpcs-model")

// NPC distribution only. Every upstream NPC/packet version module is retained.

include(":libraries:common")
include(":libraries:jdb")
include(":libraries:config")
include(":libraries:plugin-tests")

include(":libraries:packets")
include(":libraries:packets:packets-api")
include(":libraries:packets:implementations:1_21_5")
include(":libraries:packets:implementations:1_21_6")
include(":libraries:packets:implementations:1_21_9")
include(":libraries:packets:implementations:1_21_11")
include(":libraries:packets:implementations:26_1_2")
include(":libraries:packets:implementations:26_2")
include(":libraries:packets:implementations:26_3")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    
    components.all {
        if (id.group == "org.bouncycastle" && id.name.endsWith("-jdk18on")) {
            val newName = id.name.replace("-jdk18on", "-jdk15to18")
            useTarget(group = id.group, name = newName, version = "1.77")
            because("The jdk18on variant of Bouncy Castle is not compatible with the project's Java 17 environment.")
        }
    }
}

rootProject.name = "Yellow"
include(":app")

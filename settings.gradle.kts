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
    
    components {
        eachComponent {
            if (details.id.group == "org.bouncycastle" && details.id.name.endsWith("-jdk18on")) {
                val newName = details.id.name.replace("-jdk18on", "-jdk15to18")
                details.useTarget(group = details.id.group, name = newName, version = "1.77")
            }
        }
    }
}

rootProject.name = "Yellow"
include(":app")

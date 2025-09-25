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
    
    components.all { details ->
        if (details.id.group == "org.bouncycastle" && details.id.name.endsWith("-jdk18on")) {
            details.useTarget(
                group = details.id.group,
                name = details.id.name.replace("-jdk18on", "-jdk15to18"),
                version = "1.77"
            )
            details.because("The jdk18on variant of Bouncy Castle is not compatible with the project's Java 17 environment.")
        }
    }
}

rootProject.name = "Yellow"
include(":app")

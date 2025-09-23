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
    
    allConfigurations {
        resolutionStrategy {
            dependencySubstitution {
                substitute(module("org.bouncycastle:bcprov-jdk18on")).using(module("org.bouncycastle:bcprov-jdk15to18:1.77"))
                substitute(module("org.bouncycastle:bcutil-jdk18on")).using(module("org.bouncycastle:bcutil-jdk15to18:1.77"))
                substitute(module("org.bouncycastle:bcpkix-jdk18on")).using(module("org.bouncycastle:bcpkix-jdk15to18:1.77"))
            }
        }
    }
}

rootProject.name = "Yellow"
include(":app")

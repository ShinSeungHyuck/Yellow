plugins {
    id("com.android.application") version "8.1.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
}

// Java 17 호환성을 위해 빌드스크립트를 포함한 모든 의존성 해결 과정에 개입
configurations.all {
    resolutionStrategy {
        dependencySubstitution {
            substitute(module("org.bouncycastle:bcprov-jdk18on")).using(module("org.bouncycastle:bcprov-jdk15to18:1.77"))
            substitute(module("org.bouncycastle:bcutil-jdk18on")).using(module("org.bouncycastle:bcutil-jdk15to18:1.77"))
            substitute(module("org.bouncycastle:bcpkix-jdk18on")).using(module("org.bouncycastle:bcpkix-jdk15to18:1.77"))
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

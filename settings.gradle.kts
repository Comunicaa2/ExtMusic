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
        // NewPipe Extractor. JitPack compila al vuelo lo que se le pida: solo se le piden
        // las librerías de TeamNewPipe, para que no pueda servir ninguna otra.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.TeamNewPipe") }
        }
    }
}
rootProject.name = "AuraMusic"
include(":app")

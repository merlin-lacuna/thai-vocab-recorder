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
        // Onyx pen library for the Boox tablet app.
        maven { url = uri("https://repo.boox.com/repository/maven-public/") }
    }
}
rootProject.name = "ThaiVocabRecorder"
include(":app", ":core", ":boox")

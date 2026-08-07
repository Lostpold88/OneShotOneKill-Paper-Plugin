rootProject.name = "OneShotOneKill"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // Quelle der paper-api. Genau die Version, die der Server unter Server/versions faehrt.
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
    }
}

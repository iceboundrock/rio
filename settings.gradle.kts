pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
}

// 1. Name your monorepo
rootProject.name = "rio-monorepo"

// 2. Include your Gradle subproject
include(":rio-backend")

// 3. Map the subproject to its actual subdirectory path
project(":rio-backend").projectDir = file("./backend")
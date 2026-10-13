pluginManagement {
    repositories {
        // DROIDBOX: sandbox egress proxy blocks Java's TLS fingerprint;
        // dependencies are pre-fetched with curl into a local file repo.
        maven { url = uri("local-maven-repo") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("local-maven-repo") }
    }
}
rootProject.name = "DroidBox"
include(":app")

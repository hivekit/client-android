pluginManagement {
    repositories { google(); gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "hivekit-client-android"
include(":sdk")
// The JVM SDK builds without requiring an Android SDK installation.
if (providers.gradleProperty("includeAndroidSample").orNull == "true") include(":sample-android")

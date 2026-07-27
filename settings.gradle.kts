pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
    // Elly's workflow library, published locally from the workflow-kmp-migration branch.
    mavenLocal()
  }
}

rootProject.name = "smart-cxca-kmp"

include(":cxca-demo")

include(":cxca-cql")

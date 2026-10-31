pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "spanktube"

include(
    "backend:gateway",
    "backend:user-service",
    "backend:youtube-upstream-service",
    "backend:media-service",
    "backend:catalog-service",
    "backend:archive-service",
    "backend:lib:egress-starter",
    "backend:lib:event-bus",
)

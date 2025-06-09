
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.nexusPublish)
}

allprojects {
    group = "de.fabmax"
    version = "0.10.2"

    repositories {
        mavenCentral()
    }
}

nexusPublishing {
    repositories {
        repositories {
            sonatype {
                nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
                snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

                username = System.getenv("MAVEN_USERNAME")
                password = System.getenv("MAVEN_PASSWORD")
            }
        }
    }
}

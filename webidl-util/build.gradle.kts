plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.dokka)
    alias(libs.plugins.mavenPublish)
}

dependencies {
    api(libs.kotlin.coroutines)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    jvmToolchain(11)
}

tasks.test {
    testLogging {
        showStandardStreams = true
    }
}

tasks.register<Jar>("javadocJar") {
    dependsOn("dokkaGenerate")
    group = "documentation"
    archiveClassifier.set("javadoc")
    from("${projectDir}/build/dokka/html")
}

mavenPublishing {
    publishToMavenCentral()
    if (!version.toString().endsWith("-SNAPSHOT")) {
        signAllPublications()
    }

    coordinates(group.toString(), name, version.toString())

    pom {
        name.set("webidl-util")
        description.set("A parser and code-generator for WebIDL files.")
        inceptionYear.set("2020")
        url.set("https://github.com/fabmax/webidl-util/")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("fabmax")
                name.set("Max Thiele")
                url.set("https://github.com/fabmax/")
            }
        }
        scm {
            url.set("https://github.com/fabmax/webidl-util/")
            connection.set("scm:git:git://github.com/fabmax/webidl-util.git")
            developerConnection.set("scm:git:ssh://git@github.com/fabmax/webidl-util.git")
        }
    }
}
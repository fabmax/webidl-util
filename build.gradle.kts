import java.io.FileInputStream
import java.util.*

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.dokka)
    `java-gradle-plugin`
    `maven-publish`
    signing
}

group = "de.fabmax"
version = "0.8.5-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api(libs.kotlin.coroutines)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    jvmToolchain(8)
}

gradlePlugin {
    plugins {
        create("webidlPlugin") {
            id = "de.fabmax.webidl-util"
            implementationClass = "de.fabmax.webidl.gradle.WebIdlUtilPlugin"
        }
    }
}

tasks.test {
    testLogging {
        showStandardStreams = true
    }
}

publishing {
    repositories {
        maven {
            name = "localPluginRepository"
            url = uri("$projectDir/plugin-repo")
        }
    }
}

tasks.register<Jar>("javadocJar") {
    dependsOn("dokkaJavadoc")
    group = "documentation"
    archiveClassifier.set("javadoc")
    from("${buildDir}/dokka/javadoc")
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            from(components["java"])

            artifact(tasks["kotlinSourcesJar"])
            artifact(tasks["javadocJar"])

            pom {
                name.set("webidl-util")
                description.set("A parser and code-generator for WebIDL files.")
                url.set("https://github.com/fabmax/webidl-util")
                developers {
                    developer {
                        name.set("Max Thiele")
                        email.set("fabmax.thiele@gmail.com")
                        organization.set("github")
                        organizationUrl.set("https://github.com/fabmax")
                    }
                }
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/fabmax/webidl-util.git")
                    developerConnection.set("scm:git:ssh://github.com:fabmax/webidl-util.git")
                    url.set("https://github.com/fabmax/webidl-util/tree/main")
                }
            }
        }
    }

    if (File("publishingCredentials.properties").exists()) {
        val props = Properties()
        props.load(FileInputStream("publishingCredentials.properties"))

        repositories {
            maven {
                url = if (version.toString().endsWith("-SNAPSHOT")) {
                    uri("https://oss.sonatype.org/content/repositories/snapshots")
                } else {
                    uri("https://oss.sonatype.org/service/local/staging/deploy/maven2")
                }
                credentials {
                    username = props.getProperty("publishUser")
                    password = props.getProperty("publishPassword")
                }
            }
        }

        signing {
            sign(publishing.publications["mavenKotlin"])
        }
    }
}

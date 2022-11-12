import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.*

plugins {
    kotlin("jvm") version "1.7.21"
    id("org.jetbrains.dokka") version "1.7.20"
    `maven-publish`
    signing
}

group = "de.fabmax"
version = "0.7.8-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

    testImplementation("junit:junit:4.13")
}

tasks.withType<KotlinCompile>().all {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

tasks.test {
    testLogging {
        showStandardStreams = true
    }
}

tasks.register<Jar>("javadocJar") {
    dependsOn("dokkaJavadoc")
    group = "documentation"
    archiveClassifier.set("javadoc")
    from("$buildDir/dokka/javadoc")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions.apply {
    freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlin.RequiresOptIn"
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

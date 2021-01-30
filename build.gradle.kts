import java.io.File
import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.21"
    `maven-publish`
}

group = "de.fabmax"
version = "0.5.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.2")

    testImplementation("junit:junit:4.13")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions.apply {
    freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlin.RequiresOptIn"
}

if (File("publishingCredentials.properties").exists()) {
    val props = Properties()
    props.load(FileInputStream("publishingCredentials.properties"))

    publishing {
        repositories {
            maven {
                url = uri("${props.getProperty("publishRepoUrl")}/webidl-util")
                credentials {
                    username = props.getProperty("publishUser")
                    password = props.getProperty("publishPassword")
                }
            }
        }

        publications {
            create<MavenPublication>("mavenKotlin") {
                from(components["java"])

                artifact(tasks["kotlinSourcesJar"])

                pom {
                    name.set("webidl-util")
                    description.set("A parser and code-generator for WebIDL files.")
                    url.set("https://github.com/fabmax/webidl-util")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/fabmax/webidl-util.git")
                        developerConnection.set("scm:git:https://github.com/fabmax/webidl-util.git")
                        url.set("https://github.com/fabmax/webidl-util")
                    }
                }
            }
        }
    }

}

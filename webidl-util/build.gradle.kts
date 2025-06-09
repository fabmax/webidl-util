plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.dokka)
    `maven-publish`
    signing
}

dependencies {
    api(libs.kotlin.coroutines)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    jvmToolchain(8)
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

    repositories {
        maven {
            url = if (version.toString().endsWith("-SNAPSHOT")) {
                uri("https://central.sonatype.com/repository/maven-snapshots/")
            } else {
                uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            }
        }
    }
}

signing {
    val privateKey = System.getenv("GPG_PRIVATE_KEY")
    val password = System.getenv("GPG_PASSWORD")
    useInMemoryPgpKeys(privateKey, password)
    sign(publishing.publications["mavenKotlin"])
}

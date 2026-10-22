plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
    `maven-publish`
    id("org.jetbrains.dokka") version "2.2.0"
    // 0.36 supports the repository's Gradle 8 wrapper; 0.37 requires Gradle 9.
    id("com.vanniktech.maven.publish.base") version "0.36.0"
}

group = "io.hivekit"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()
val centralRelease = providers.gradleProperty("centralRelease").map { it.toBooleanStrict() }.orElse(false).get()
require(version.toString().matches(Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"""))) {
    "Invalid releaseVersion '$version'; use a version such as 0.1.0 or 0.1.0-rc.1"
}
if (centralRelease) {
    require(providers.gradleProperty("releaseVersion").isPresent && !version.toString().endsWith("-SNAPSHOT", ignoreCase = true)) {
        "Central releases require an explicit non-SNAPSHOT -PreleaseVersion, for example -PreleaseVersion=0.1.0"
    }
}

// Keep the runtime version, binary and sources in agreement for every release.
val sdkVersion = version.toString()
val generatedVersionDirectory = layout.buildDirectory.dir("generated/sources/sdkVersion/kotlin")
val generateSdkVersion by tasks.registering {
    inputs.property("sdkVersion", sdkVersion)
    outputs.dir(generatedVersionDirectory)
    doLast {
        val source = generatedVersionDirectory.get().file("io/hivekit/client/SdkVersion.kt").asFile
        source.parentFile.mkdirs()
        source.writeText("package io.hivekit.client\n\ninternal const val SDK_VERSION: String = \"$sdkVersion\"\n")
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) }
    sourceSets.named("main") { kotlin.srcDir(generatedVersionDirectory) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}
tasks.named("compileKotlin") { dependsOn(generateSdkVersion) }
tasks.named("sourcesJar") { dependsOn(generateSdkVersion) }
tasks.matching { it.name.startsWith("dokkaGenerate") }.configureEach { dependsOn(generateSdkVersion) }
tasks.named<Jar>("javadocJar") {
    from(tasks.dokkaGeneratePublicationHtml.flatMap { it.outputDirectory })
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.ktor:ktor-client-core:3.3.3")
    implementation("io.ktor:ktor-client-okhttp:3.3.3")
    implementation("io.ktor:ktor-client-websockets:3.3.3")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.2.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("sdk") {
            from(components["java"])
            artifactId = "client-android"
            pom {
                name.set("Hivekit Android and JVM client")
                description.set("Kotlin coroutine SDK for Hivekit")
                url.set("https://github.com/hivekit/client-android")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("hivekit")
                        name.set("Hivekit")
                        url.set("https://github.com/hivekit")
                        organization.set("Hivekit")
                        organizationUrl.set("https://hivekit.io")
                    }
                }
                scm {
                    url.set("https://github.com/hivekit/client-android")
                    connection.set("scm:git:https://github.com/hivekit/client-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/hivekit/client-android.git")
                    tag.set(if (centralRelease) "v$sdkVersion" else "HEAD")
                }
            }
        }
    }
    repositories {
        maven {
            name = "build"
            url = uri(layout.buildDirectory.dir("repository"))
        }
    }
}

mavenPublishing {
    coordinates(group.toString(), "client-android", sdkVersion)
    // Ordinary builds and local publications need no signing key or Portal token.
    if (centralRelease) {
        publishToMavenCentral(automaticRelease = false)
        signAllPublications()
    }
}

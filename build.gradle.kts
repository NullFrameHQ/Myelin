plugins {
    id("fabric-loom") version "1.13.6"
    id("maven-publish")
}

group = "com.me.nullframe"
version = "0.1.1"

repositories {
    mavenLocal {
        content { includeGroup("com.nullframe") }
    }
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://api.modrinth.com/maven")
    maven("https://maven.quiltmc.org/repository/release/")
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    mappings("net.fabricmc:yarn:1.21.1+build.1:v2")
    modImplementation("net.fabricmc:fabric-loader:0.17.0")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.104.0+1.21.1")
    implementation("com.google.code.gson:gson:2.10.1")
    //Add YACL (for client)
    modImplementation("maven.modrinth:yacl:3.8.1+1.21.1-fabric")
    // YACL's supporting libraries for development runs.
    runtimeOnly("org.quiltmc.parsers:gson:0.2.1")
    runtimeOnly("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    // Build Core separately and publish it to Maven Local before building Myelin.
    modImplementation("com.nullframe:nullframe-core:0.1.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

val myelinLicenseFiles = listOf("LICENSE.md")
tasks.named<Jar>("jar") {
    from(myelinLicenseFiles)
}
tasks.named<Jar>("sourcesJar") {
    from(myelinLicenseFiles)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

loom {
    mods {
        create("myelin") {
            sourceSet(sourceSets.main.get())
        }
    }
}

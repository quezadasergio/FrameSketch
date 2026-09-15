plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.framesketch"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

javafx {
    version = "25.0.2"
    modules("javafx.controls", "javafx.graphics")
}

dependencies {
    implementation("uk.co.caprica:vlcj:4.12.1")
    implementation("uk.co.caprica:vlcj-javafx:1.2.1")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("org.kordamp.ikonli:ikonli-javafx:12.4.0")
    implementation("org.kordamp.ikonli:ikonli-materialdesign2-pack:12.4.0")
}

application {
    mainClass.set("com.framesketch.app.Launcher")
}

tasks.named<JavaExec>("run") {
    jvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED"
    )
}

tasks.shadowJar {
    archiveBaseName.set("FrameSketch")
    archiveClassifier.set("all")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    manifest {
        attributes["Main-Class"] = "com.framesketch.app.Launcher"
        attributes["Implementation-Title"] = "FrameSketch"
        attributes["Implementation-Version"] = project.version
    }
}

tasks.register("fatJar") {
    group = "build"
    description = "Creates a fat JAR (alias for shadowJar)"
    dependsOn(tasks.shadowJar)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

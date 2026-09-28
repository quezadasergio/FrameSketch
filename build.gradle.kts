import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

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
        "--enable-native-access=ALL-UNNAMED",
        "--enable-native-access=javafx.graphics",
        "--sun-misc-unsafe-memory-access=allow"
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

val distDir = layout.buildDirectory.dir("dist")
val packagingDir = layout.buildDirectory.dir("packaging")
val ffmpegBundleDir = packagingDir.map { it.dir("app-content/ffmpeg") }
val jpackageInputDir = packagingDir.map { it.dir("input") }

tasks.register("prepareFfmpegBundle") {
    group = "distribution"
    description = "Downloads a portable FFmpeg/ffprobe build for the current OS (or copies packaging/ffmpeg-prebuilt)"
    outputs.dir(ffmpegBundleDir)
    doLast {
        val dest = ffmpegBundleDir.get().asFile
        dest.mkdirs()
        dest.listFiles()?.forEach { it.delete() }

        val prebuilt = file("packaging/ffmpeg-prebuilt")
        val unixBin = prebuilt.resolve("ffmpeg")
        val winBin = prebuilt.resolve("ffmpeg.exe")
        if (unixBin.isFile || winBin.isFile) {
            prebuilt.listFiles()?.forEach { src ->
                src.copyTo(dest.resolve(src.name), overwrite = true)
                dest.resolve(src.name).setExecutable(true)
            }
            logger.lifecycle("Using FFmpeg from packaging/ffmpeg-prebuilt")
            return@doLast
        }

        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val asset = when {
            os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm")) ->
                "ffmpeg-master-latest-macosarm64-gpl.zip"
            os.contains("mac") ->
                "ffmpeg-master-latest-macos64-gpl.zip"
            os.contains("win") && (arch.contains("aarch64") || arch.contains("arm")) ->
                "ffmpeg-master-latest-winarm64-gpl.zip"
            os.contains("win") ->
                "ffmpeg-master-latest-win64-gpl.zip"
            else -> error("No hay build portátil de FFmpeg para $os / $arch. Coloca los binarios en packaging/ffmpeg-prebuilt/")
        }
        val url = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/$asset"
        val zip = packagingDir.get().asFile.resolve("ffmpeg-download.zip")
        zip.parentFile.mkdirs()
        logger.lifecycle("Downloading $url")
        URI.create(url).toURL().openStream().use { input ->
            Files.copy(input, zip.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        val unpacked = packagingDir.get().asFile.resolve("ffmpeg-unpacked")
        unpacked.deleteRecursively()
        unpacked.mkdirs()
        ZipFile(zip).use { zf ->
            zf.entries().asSequence().forEach { entry ->
                val out = unpacked.resolve(entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }

        val binDir = unpacked.walkTopDown().firstOrNull { it.isDirectory && it.name == "bin" }
            ?: error("El zip de FFmpeg no contiene una carpeta bin/")
        listOf("ffmpeg", "ffmpeg.exe", "ffprobe", "ffprobe.exe").forEach { name ->
            val src = binDir.resolve(name)
            if (src.isFile) {
                val copied = src.copyTo(dest.resolve(name), overwrite = true)
                copied.setExecutable(true)
            }
        }
        val hasFfmpeg = dest.resolve("ffmpeg").isFile || dest.resolve("ffmpeg.exe").isFile
        check(hasFfmpeg) { "No se encontró ffmpeg en el zip descargado" }
        logger.lifecycle("FFmpeg listo en ${dest.absolutePath}")
    }
}

tasks.register("prepareJpackageInput") {
    group = "distribution"
    description = "Copies the fat JAR into a clean jpackage input directory"
    dependsOn("fatJar")
    doLast {
        val input = jpackageInputDir.get().asFile
        input.mkdirs()
        input.listFiles()?.forEach { it.delete() }
        val jar = layout.buildDirectory.get().asFile.resolve("libs/FrameSketch-${project.version}-all.jar")
        check(jar.isFile) { "No existe $jar. Ejecuta ./gradlew fatJar" }
        jar.copyTo(input.resolve(jar.name), overwrite = true)
    }
}

fun jpackageExecutable(): File {
    val javaHome = System.getProperty("java.home")
        ?: error("java.home no está definido")
    val bin = if (System.getProperty("os.name").lowercase().contains("win")) "jpackage.exe" else "jpackage"
    val exe = File(javaHome, "bin/$bin")
    if (exe.isFile) {
        return exe
    }
    val toolchainJava = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }.get().metadata.installationPath.asFile
    val toolchainExe = File(toolchainJava, "bin/$bin")
    check(toolchainExe.isFile) {
        "No se encontró jpackage. Instala JDK 25 (no solo JRE) y vuelve a intentar."
    }
    return toolchainExe
}

fun runProcess(vararg command: String): Int {
    return ProcessBuilder(*command)
        .inheritIO()
        .start()
        .waitFor()
}

fun generateMacIcns(png: File, work: File): File? {
    if (!png.isFile) {
        return null
    }
    val iconset = work.resolve("FrameSketch.iconset")
    iconset.deleteRecursively()
    iconset.mkdirs()
    val sizes = listOf(16, 32, 128, 256, 512)
    for (size in sizes) {
        runProcess("sips", "-z", "$size", "$size", png.absolutePath, "--out", iconset.resolve("icon_${size}x$size.png").absolutePath)
        runProcess("sips", "-z", "${size * 2}", "${size * 2}", png.absolutePath, "--out", iconset.resolve("icon_${size}x$size@2x.png").absolutePath)
    }
    val icns = work.resolve("FrameSketch.icns")
    runProcess("iconutil", "-c", "icns", iconset.absolutePath, "-o", icns.absolutePath)
    return icns.takeIf { it.isFile }
}

fun runJpackage(type: String, extraArgs: List<String>) {
    val dest = distDir.get().asFile
    dest.mkdirs()
    val input = jpackageInputDir.get().asFile
    val ffmpeg = ffmpegBundleDir.get().asFile
    val jarName = "FrameSketch-${project.version}-all.jar"
    val args = mutableListOf(
        jpackageExecutable().absolutePath,
        "--type", type,
        "--name", "FrameSketch",
        "--app-version", project.version.toString(),
        "--vendor", "FrameSketch",
        "--description", "Video playback and sports play analysis. Requires VLC.",
        "--input", input.absolutePath,
        "--main-jar", jarName,
        "--main-class", "com.framesketch.app.Launcher",
        "--dest", dest.absolutePath,
        "--app-content", ffmpeg.absolutePath,
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "--enable-native-access=javafx.graphics",
        "--java-options", "--sun-misc-unsafe-memory-access=allow"
    )
    args.addAll(extraArgs)
    val code = ProcessBuilder(args).inheritIO().start().waitFor()
    check(code == 0) { "jpackage falló con código $code" }
}

tasks.register("packageMac") {
    group = "distribution"
    description = "Builds a macOS .dmg with bundled FFmpeg (run on a Mac). VLC is not bundled."
    dependsOn("prepareJpackageInput", "prepareFfmpegBundle")
    doLast {
        val os = System.getProperty("os.name").lowercase()
        check(os.contains("mac")) {
            "packageMac debe ejecutarse en macOS. En esta máquina usa ./gradlew packageWin o un runner de Mac."
        }
        val work = packagingDir.get().asFile
        work.mkdirs()
        val extra = mutableListOf(
            "--mac-package-identifier", "com.framesketch.app",
            "--mac-package-name", "FrameSketch",
            "--mac-app-category", "public.app-category.video"
        )
        generateMacIcns(file("src/main/resources/splash.png"), work)?.let {
            extra.addAll(listOf("--icon", it.absolutePath))
        }
        runJpackage("dmg", extra)
        logger.lifecycle("Instalador Mac: ${distDir.get().asFile.absolutePath}")
        logger.lifecycle("VLC no va en el paquete. El usuario debe instalarlo (brew install --cask vlc).")
    }
}

tasks.register("packageWin") {
    group = "distribution"
    description = "Builds a Windows .exe installer with bundled FFmpeg (run on Windows). Requires WiX. VLC is not bundled."
    dependsOn("prepareJpackageInput", "prepareFfmpegBundle")
    doLast {
        val os = System.getProperty("os.name").lowercase()
        check(os.contains("win")) {
            "packageWin debe ejecutarse en Windows. Desde este Mac no se puede generar un .exe."
        }
        runJpackage(
            "exe",
            listOf(
                "--win-shortcut",
                "--win-menu",
                "--win-menu-group", "FrameSketch",
                "--win-dir-chooser"
            )
        )
        logger.lifecycle("Instalador Windows: ${distDir.get().asFile.absolutePath}")
        logger.lifecycle("VLC no va en el paquete. El usuario debe instalarlo (winget install VideoLAN.VLC).")
    }
}

tasks.register("packageApp") {
    group = "distribution"
    description = "Builds the native installer for the current OS (Mac .dmg or Windows .exe)"
    val os = System.getProperty("os.name").lowercase()
    if (os.contains("mac")) {
        dependsOn("packageMac")
    } else if (os.contains("win")) {
        dependsOn("packageWin")
    } else {
        doLast {
            error("Solo hay instaladores para macOS y Windows. Usa packageMac o packageWin en el SO correspondiente.")
        }
    }
}

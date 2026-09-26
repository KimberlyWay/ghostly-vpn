import java.net.URI
import java.util.zip.ZipInputStream
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
    implementation(libs.jna.platform)
    // Now playing (Windows SMTC): the same small library the Kasane media bar uses.
    implementation(files("libs/media-player-info-0.1.0.jar"))
}

kotlin {
    jvmToolchain(21)
}

/**
 * Xray core for the host OS (XTLS/Xray-core release zip), unpacked into core/<os>-<arch>/ which is
 * Compose's per-platform app-resources layout. CI builds every OS on its own runner.
 */
abstract class FetchXrayDesktop : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val asset: Property<String>
    @get:OutputDirectory abstract val target: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dir = target.get().asFile
        val marker = File(dir, ".version")
        if (marker.isFile && marker.readText() == version.get() + "/" + asset.get()) return
        dir.deleteRecursively()
        dir.mkdirs()
        val url = "https://github.com/XTLS/Xray-core/releases/download/v${version.get()}/${asset.get()}"
        logger.lifecycle("Downloading $url")
        val keep = setOf("xray", "xray.exe", "wintun.dll", "geoip.dat", "geosite.dat", "LICENSE", "LICENSE-Wintun")
        ZipInputStream(URI(url).toURL().openStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory || e.name.substringAfterLast('/') !in keep) continue
                val out = File(dir, e.name.substringAfterLast('/'))
                out.outputStream().use { zip.copyTo(it) }
                if (out.name == "xray") out.setExecutable(true, false)
            }
        }
        marker.writeText(version.get() + "/" + asset.get())
    }
}

val hostOs: String = System.getProperty("os.name").lowercase().let {
    when {
        "win" in it -> "windows"
        "mac" in it -> "macos"
        else -> "linux"
    }
}
val hostArch: String = System.getProperty("os.arch").lowercase().let { if (it == "aarch64" || it == "arm64") "arm64" else "x64" }
val xrayAsset = when (hostOs) {
    "windows" -> "Xray-windows-64.zip"
    "macos" -> if (hostArch == "arm64") "Xray-macos-arm64-v8a.zip" else "Xray-macos-64.zip"
    else -> if (hostArch == "arm64") "Xray-linux-arm64-v8a.zip" else "Xray-linux-64.zip"
}

val fetchXrayCore = tasks.register<FetchXrayDesktop>("fetchXrayCore") {
    version = libs.versions.xrayDesktop
    asset = xrayAsset
    target = layout.projectDirectory.dir("core/$hostOs-$hostArch")
}
/**
 * mihomo core (legiz-ru/Prizrak-Core) next to Xray in the same core/<os>-<arch>/ folder, as
 * prizrak-core(.exe). Windows ships a zip with one exe, the others a gzipped binary.
 */
abstract class FetchMihomoDesktop : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val asset: Property<String>
    @get:Input abstract val exeName: Property<String>
    @get:Internal abstract val target: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dir = target.get().asFile.apply { mkdirs() }
        val out = File(dir, exeName.get())
        val marker = File(dir, ".mihomo-version")
        if (out.isFile && marker.isFile && marker.readText() == version.get() + "/" + asset.get()) return
        val url = "https://github.com/legiz-ru/Prizrak-Core/releases/download/v${version.get()}/${asset.get()}"
        logger.lifecycle("Downloading $url")
        val tmp = File(dir, out.name + ".part")
        URI(url).toURL().openStream().use { input ->
            if (asset.get().endsWith(".zip")) {
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: throw GradleException("no executable in ${asset.get()}")
                        if (!e.isDirectory && e.name.endsWith(".exe")) {
                            tmp.outputStream().use { zip.copyTo(it) }
                            break
                        }
                    }
                }
            } else {
                java.util.zip.GZIPInputStream(input).use { gz -> tmp.outputStream().use { gz.copyTo(it) } }
            }
        }
        if (out.exists()) out.delete()
        tmp.renameTo(out)
        out.setExecutable(true, false)
        marker.writeText(version.get() + "/" + asset.get())
    }
}

val mihomoAsset = "prizrak-core-" + when (hostOs) {
    "windows" -> if (hostArch == "arm64") "windows-arm64" else "windows-amd64-v1"
    "macos" -> if (hostArch == "arm64") "darwin-arm64" else "darwin-amd64-v1"
    else -> if (hostArch == "arm64") "linux-arm64" else "linux-amd64-v1"
} + "-v" + libs.versions.prizrakCore.get() + if (hostOs == "windows") ".zip" else ".gz"

val fetchMihomoCore = tasks.register<FetchMihomoDesktop>("fetchMihomoCore") {
    version = libs.versions.prizrakCore
    asset = mihomoAsset
    exeName = if (hostOs == "windows") "prizrak-core.exe" else "prizrak-core"
    target = layout.projectDirectory.dir("core/$hostOs-$hostArch")
    // Xray's task wipes the folder when its version changes, so it goes first.
    mustRunAfter(fetchXrayCore)
    outputs.upToDateWhen { false }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(fetchXrayCore, fetchMihomoCore) }

compose.desktop {
    application {
        mainClass = "app.ghostly.desktop.MainKt"
        jvmArgs += listOf("-Dsun.java2d.uiScale.enabled=true", "-Xmx512m")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Ghostly"
            packageVersion = "0.1.7"
            description = "Ghostly VPN"
            vendor = "Ghostly"
            copyright = "GPL-3.0"
            appResourcesRootDir = layout.projectDirectory.dir("core")
            modules("java.naming", "jdk.crypto.ec", "java.net.http", "jdk.unsupported", "java.instrument", "java.management")

            windows {
                menuGroup = "Ghostly"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6b0d7f3e-2f5c-4f3c-9d44-6a0c5a8f1e21"
                iconFile = project.file("icons/ghostly.ico")
            }
            macOS {
                bundleID = "app.ghostly.desktop"
                iconFile = project.file("icons/ghostly.icns")
            }
            linux {
                iconFile = project.file("icons/ghostly.png")
                packageName = "ghostly"
            }
        }
    }
}

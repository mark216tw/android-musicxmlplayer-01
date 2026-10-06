import java.net.URI
import java.net.HttpURLConnection
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val skipNative = providers.gradleProperty("skipNative").orNull?.let { it.isBlank() || it.toBooleanStrictOrNull() == true } ?: false
val appVersionCode = 11
val appVersionName = "1.0.1-prerelease"
val buildInfoResources = layout.buildDirectory.dir("generated/build-info/res")
val generateBuildInfo = tasks.register("generateBuildInfo") {
    outputs.dir(buildInfoResources)
    outputs.upToDateWhen { false }
    doLast {
        val buildNumber = DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss").withZone(ZoneOffset.UTC).format(Instant.now()) + ".$appVersionCode"
        val values = buildInfoResources.get().dir("values").asFile.apply { mkdirs() }
        values.resolve("build_info.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources><string name=\"build_number\">$buildNumber</string></resources>\n"
        )
    }
}
if (skipNative && gradle.startParameter.taskNames.any {
        Regex("^(assemble|bundle|package|install)", RegexOption.IGNORE_CASE).containsMatchIn(it.substringAfterLast(':'))
    }) {
    throw GradleException("-PskipNative is only for JVM tests and lint; it cannot package or install an APK.")
}
android {
    namespace = "com.musicxml.player"
    compileSdk = 35
    if (!skipNative) ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.musicxml.player"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "APP_VERSION_NAME", "\"$appVersionName\"")
        buildConfigField("int", "APP_VERSION_CODE", appVersionCode.toString())
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        if (!skipNative) externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { prefab = true; buildConfig = true }
    sourceSets.getByName("main").res.srcDir(buildInfoResources)
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes {
        create("prerelease") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    if (!skipNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}
dependencies {
    implementation("com.google.oboe:oboe:1.9.3")
    testImplementation("junit:junit:4.13.2")
    // JVM tests use a real JSON parser rather than the Android SDK's stub classes.
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
}

// Pin upstream revisions; downloads are build inputs, never runtime network requests.
val audioInputs = tasks.register("prepareAudioInputs") {
    val output = layout.buildDirectory.dir("generated/audio")
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val sfRevision = "684543d5e5efaef08d02be50dcda8d552478fa60"
        val tsfRevision = "853a0a171759f1ddba0de1442133a75912bbeffa"
        val sources = mapOf(
            "GeneralUser-GS.sf2" to "https://raw.githubusercontent.com/mrbumpy409/GeneralUser-GS/$sfRevision/GeneralUser-GS.sf2",
            "GeneralUser-LICENSE.txt" to "https://raw.githubusercontent.com/mrbumpy409/GeneralUser-GS/$sfRevision/documentation/LICENSE.txt",
            "tsf.h" to "https://raw.githubusercontent.com/schellingb/TinySoundFont/$tsfRevision/tsf.h",
            "TinySoundFont-LICENSE.txt" to "https://raw.githubusercontent.com/schellingb/TinySoundFont/$tsfRevision/LICENSE",
            "Oboe-LICENSE.txt" to "https://raw.githubusercontent.com/google/oboe/1.9.3/LICENSE"
        )
        sources.forEach { (name, url) ->
            val destination = File(directory, name)
            if (!destination.exists()) {
                if (name == "GeneralUser-GS.sf2") {
                    val length = 32319396L
                    val temporary = File(directory, "$name.download")
                    val connection = URI(url).toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 30000; connection.readTimeout = 300000
                    check(connection.responseCode == 200) { "SoundFont download failed: ${connection.responseCode}" }
                    connection.inputStream.use { input -> FileOutputStream(temporary).use { input.copyTo(it, 65536) } }
                    check(temporary.length() == length) { "SoundFont download is incomplete" }
                    val digest = MessageDigest.getInstance("SHA-1")
                    digest.update("blob $length\u0000".toByteArray())
                    temporary.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        var count = input.read(buffer)
                        while (count != -1) { digest.update(buffer, 0, count); count = input.read(buffer) }
                    }
                    check(digest.digest().joinToString("") { "%02x".format(it) } == "298b552d2e9d1307e03e5c5c99d2c046aaed9ec3") { "SoundFont checksum mismatch" }
                    check(temporary.renameTo(destination))
                    return@forEach
                }
                val temporary = File(directory, "$name.download")
                val connection = URI(url).toURL().openConnection().apply { connectTimeout = 30000; readTimeout = 120000 }
                val offset = temporary.length()
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val append = (connection as HttpURLConnection).responseCode == 206
                logger.lifecycle("Downloading $name${if (append) " (resuming at $offset bytes)" else ""}")
                connection.getInputStream().use { input ->
                    FileOutputStream(temporary, append).use { output ->
                        val buffer = ByteArray(65536)
                        var count = input.read(buffer)
                        var downloaded = if (append) offset else 0L
                        var reported = downloaded
                        while (count != -1) {
                            output.write(buffer, 0, count); downloaded += count
                            if (downloaded - reported >= 4 * 1024 * 1024) {
                                logger.lifecycle("$name: ${downloaded / 1024 / 1024} MB")
                                reported = downloaded
                            }
                            count = input.read(buffer)
                        }
                    }
                }
                check(temporary.renameTo(destination)) { "Cannot save $name" }
            }
        }
        val sf = File(directory, "GeneralUser-GS.sf2")
        check(sf.length() == 32319396L) { "SoundFont download is incomplete" }
    }
}
val audioAssets = tasks.register<Sync>("prepareAudioAssets") {
    dependsOn(audioInputs)
    from(layout.buildDirectory.dir("generated/audio")) { include("*.sf2", "*-LICENSE.txt") }
    into(layout.buildDirectory.dir("generated/audioAssets"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/audioAssets"))
tasks.named("preBuild") { dependsOn(audioAssets, generateBuildInfo) }
tasks.configureEach {
    if (!skipNative && (name.startsWith("configureCMake") || name.startsWith("buildCMake"))) dependsOn(audioInputs)
}

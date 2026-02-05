/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

import com.android.build.api.variant.ResValue
import com.android.build.gradle.internal.UsesSdkComponentsBuildService
import com.android.build.gradle.internal.dsl.SdkComponentsImpl
import com.android.repository.Revision
import java.io.ByteArrayOutputStream
import org.eclipse.jgit.api.ArchiveCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.archive.TarFormat
import org.eclipse.jgit.lib.ObjectId
import org.gradle.kotlin.dsl.environment
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

buildscript {
    dependencies {
        classpath(libs.jgit)
        classpath(libs.jgit.archive)
    }
}

typealias VersionTriple = Triple<String?, Int, ObjectId>

fun describeVersion(git: Git): VersionTriple {
    // jgit doesn't provide a nice way to get strongly-typed objects from its `describe` command
    val describeStr = git.describe().setLong(true).call()

    return if (describeStr != null) {
        val pieces = describeStr.split('-').toMutableList()
        val commit = git.repository.resolve(pieces.removeLast().substring(1))
        val count = pieces.removeLast().toInt()
        val tag = pieces.joinToString("-")

        Triple(tag, count, commit)
    } else {
        val log = git.log().call().iterator()
        val head = log.next()
        var count = 1

        while (log.hasNext()) {
            log.next()
            ++count
        }

        Triple(null, count, head.id)
    }
}

fun getVersionCode(triple: VersionTriple): Int {
    val tag = triple.first
    val (major, minor) = if (tag != null) {
        if (!tag.startsWith('v')) {
            throw IllegalArgumentException("Tag does not begin with 'v': $tag")
        }

        val pieces = tag.substring(1).split('.')
        if (pieces.size != 2) {
            throw IllegalArgumentException("Tag is not in the form 'v<major>.<minor>': $tag")
        }

        Pair(pieces[0].toInt(), pieces[1].toInt())
    } else {
        Pair(0, 0)
    }

    // 8 bits for major version, 8 bits for minor version, and 8 bits for git commit count
    assert(major in 0 until 1.shl(8))
    assert(minor in 0 until 1.shl(8))
    assert(triple.second in 0 until 1.shl(8))

    return major.shl(16) or minor.shl(8) or triple.second
}

fun getVersionName(git: Git, triple: VersionTriple): String {
    val tag = triple.first?.replace(Regex("^v"), "") ?: "NONE"

    return buildString {
        append(tag)

        if (triple.second > 0) {
            append(".r")
            append(triple.second)

            append(".g")
            git.repository.newObjectReader().use {
                append(it.abbreviate(triple.third).name())
            }
        }
    }
}

val git = Git.open(File(rootDir, ".git"))!!
val gitVersionTriple = describeVersion(git)
val gitVersionCode = getVersionCode(gitVersionTriple)
val gitVersionName = getVersionName(git, gitVersionTriple)

val projectUrl = "https://github.com/chenxiaolong/RSAF"

val extraDir = layout.buildDirectory.map { it.dir("extra") }
val archiveDir = extraDir.map { it.dir("archive") }
val rcbridgeDir = extraDir.map { it.dir("rcbridge") }
val rcbridgeAar = rcbridgeDir.map { it.file("rcbridge.aar") }

android {
    namespace = "com.chiller3.rsaf"

    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.chiller3.rsaf"
        minSdk = 28
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = gitVersionName

        base.archivesName.set("RSAF-$versionName")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PROJECT_URL_AT_COMMIT",
            "\"${projectUrl}/tree/${gitVersionTriple.third.name}\"")

        buildConfigField("String", "DOCUMENTS_AUTHORITY",
            "APPLICATION_ID + \".documents\"")

        resValue("string", "app_name", "@string/app_name_release")
    }
    signingConfigs {
        create("release") {
            val keystore = System.getenv("RELEASE_KEYSTORE")
            storeFile = if (keystore != null) { File(keystore) } else { null }
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSPHRASE")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSPHRASE")
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"

            resValue("string", "app_name", "@string/app_name_debug")
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_21)
        targetCompatibility(JavaVersion.VERSION_21)
    }
    buildFeatures {
        buildConfig = true
        resValues = true
        viewBinding = true
    }
    splits {
        // Split by ABI because compiled golang code is huge and a universal APK is nearly 200 MiB
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

androidComponents.onVariants { variant ->
    variant.sources.assets!!.addGeneratedSourceDirectory(archive) {
        project.objects.directoryProperty().apply {
            set(archiveDir)
        }
    }

    // This is set here so that applicationIdSuffix will be respected.
    variant.resValues.put(
        variant.makeResValueKey("string", "documents_authority"),
        ResValue("${variant.applicationId.get()}.documents"),
    )
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.biometric)
    implementation(libs.core.ktx)
    implementation(libs.exifinterface)
    implementation(libs.fragment.ktx)
    implementation(libs.preference.ktx)
    implementation(libs.material)
    implementation(libs.tink.android)
    implementation(files(rcbridgeAar))

    // Included only to work around R8 complaining about missing annotation classes referenced by
    // the Tink transitive dependency
    implementation(libs.spotbugs)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
}

val archive = tasks.register("archive") {
    inputs.property("gitVersionTriple.third", gitVersionTriple.third)

    val outputFile = archiveDir.map { it.file("archive.tar") }
    outputs.file(outputFile)

    doLast {
        val format = "tar_for_task_$name"

        ArchiveCommand.registerFormat(format, TarFormat())
        try {
            outputFile.get().asFile.outputStream().use {
                git.archive()
                    .setTree(git.repository.resolve(gitVersionTriple.third.name))
                    .setFormat(format)
                    .setOutputStream(it)
                    .call()
            }
        } finally {
            ArchiveCommand.unregisterFormat(format)
        }
    }
}

interface InjectedExecOps {
    @get:Inject val execOps: ExecOperations
}

val rcbridgeSrcDir = File(rootDir, "rcbridge")

val golang = tasks.register("golang") {
    val goDir = File(File(rootDir, "external"), "go")
    val goSrcDir = File(goDir, "src")
    val goBinDir = File(goDir, "bin")
    val goGitDir = File(File(File(File(rootDir, ".git"), "modules"), "external"), "go")

    inputs.files(
        File(goGitDir, "HEAD"),
    )
    outputs.files(
        File(goBinDir, "go"),
        File(goBinDir, "gofmt"),
    )

    val injected = project.objects.newInstance<InjectedExecOps>()

    doLast {
        injected.execOps.exec {
            if (DefaultNativePlatform.getCurrentOperatingSystem().isWindows) {
                executable("cmd.exe")
                args("/C", "make.bat")
            } else {
                executable("./make.bash")
            }
            workingDir(goSrcDir)
        }
    }
}

val goenv = tasks.register("goenv") {
    val envVars = arrayOf(
        "GOPROXY",
        "GOSUMDB",
        "GOFLAGS",
    ).associateWith { System.getenv(it) }
    val goModFile = File(rcbridgeSrcDir, "go.mod")
    val outputFile = extraDir.map { it.file("go.env") }

    // Rebuild if the environment variables change.
    inputs.properties(envVars.map { "golang.env.${it.key}" to (it.value ?: "") }.toMap())
    inputs.files(goModFile)
    outputs.files(outputFile)

    doLast {
        val defaultEnvVars = mapOf(
            // Needed for building on Fedora prior to:
            // https://src.fedoraproject.org/rpms/golang/c/1a696ebca1b2d5227921924d3f9885e18cf445b5
            "GOPROXY" to "https://proxy.golang.org,direct",
            "GOSUMDB" to "sum.golang.org",
            "GOFLAGS" to "-ldflags=-buildid= -buildvcs=false",
        )

        defaultEnvVars + envVars

        val properties = Properties()
        (defaultEnvVars.keys + envVars.keys).forEach { key ->
            properties[key] = envVars[key] ?: defaultEnvVars[key]
        }

        outputFile.get().asFile.writer().use {
            properties.store(it, null)
        }
    }
}

fun addGoEnvironment(options: ProcessForkOptions) {
    goenv.get().outputs.files.forEach { file ->
        val properties = Properties()
        file.reader().use { properties.load(it) }
        properties.forEach { options.environment(it.key.toString(), it.value) }
    }
}

val gomobile = tasks.register("gomobile") {
    val binDir = layout.buildDirectory.map { it.dir("bin") }

    inputs.files(
        File(rcbridgeSrcDir, "go.sum"),
        golang.map { it.outputs.files },
        goenv.map { it.outputs.files },
    )
    outputs.files(
        binDir.map { it.file("gobind") },
        binDir.map { it.file("gomobile") },
    )

    val injected = project.objects.newInstance<InjectedExecOps>()

    doLast {
        val goExecutable = golang.get().outputs.files.find { it.name == "go" }!!
        val outputStream = ByteArrayOutputStream()

        injected.execOps.exec {
            executable(goExecutable)
            args("mod", "graph")
            addGoEnvironment(this)
            workingDir(rcbridgeSrcDir)
            standardOutput = outputStream
        }

        val outputText = outputStream.toString(Charsets.UTF_8)
        val prefix = "rcbridge golang.org/x/mobile@"
        val version = outputText.lineSequence()
            .find { it.startsWith(prefix) }
            ?.substring(prefix.length)
            ?: throw IllegalStateException("go.sum does not contain gomobile version")

        injected.execOps.exec {
            executable(goExecutable)
            args(
                "install",
                "golang.org/x/mobile/cmd/gobind@$version",
                "golang.org/x/mobile/cmd/gomobile@$version",
            )

            environment("GOBIN", binDir.get().asFile.absolutePath)
            addGoEnvironment(this)

            workingDir(rcbridgeSrcDir)
        }
    }
}

val gowrapper = tasks.register("gowrapper") {
    val gowrapperDir = File(rcbridgeSrcDir, "gowrapper")
    val binDir = layout.buildDirectory.map { it.dir("bin") }

    inputs.files(
        File(gowrapperDir, "go.go"),
        File(rcbridgeSrcDir, "go.mod"),
        File(rcbridgeSrcDir, "go.sum"),
        golang.map { it.outputs.files },
        goenv.map { it.outputs.files },
    )
    outputs.files(
        binDir.map { it.file("go") },
    )

    val injected = project.objects.newInstance<InjectedExecOps>()

    doLast {
        val goExecutable = golang.get().outputs.files.find { it.name == "go" }!!

        injected.execOps.exec {
            executable(goExecutable)
            args("install", "go.go")

            environment("GOBIN", binDir.get().asFile.absolutePath)
            addGoEnvironment(this)

            workingDir(gowrapperDir)
        }
    }
}

val rcbridge = tasks.register("rcbridge") {
    project.objects.newInstance<UsesSdkComponentsBuildService>().let { usesSdkComponents ->
        usesSdkComponents.initializeSdkComponentsBuildService(this)
        val sdkComponentsBuildService = usesSdkComponents.sdkComponentsBuildService.get()

        // AGP is currently set up so that the NDK auto-installation only happens when the C++
        // functionality is enabled. We want that behavior even though we're only using the NDK for
        // building Go code.
        val sdkComponents = androidComponents.sdkComponents as SdkComponentsImpl
        val ndkHandler = sdkComponentsBuildService.versionedNdkHandler(
            sdkComponents.ndkVersion.get(),
            sdkComponents.ndkPath.takeIf { it.isPresent }?.get(),
        )
        ndkHandler.getNdkPlatform(downloadOkay = true)

        // Query for the path to android.jar. This forces the SdkFullLoadingStrategy to initialize,
        // which calls SdkHandler.initTarget(), which calls DefaultSdkLoader.getTargetInfo(), which
        // installs missing SDK components. Like the NDK installation above, this is hacky, but
        // works well enough.
        sdkComponentsBuildService
            .sdkLoader(
                project.provider { "android-${android.compileSdk}" },
                project.provider { Revision.parseRevision(android.buildToolsVersion) },
            )
            .androidJarProvider
            .get()
    }

    val tempDir = rcbridgeDir.map { it.dir("temp") }

    inputs.files(
        File(rcbridgeSrcDir, "go.mod"),
        File(rcbridgeSrcDir, "go.sum"),
        File(rcbridgeSrcDir, "rcbridge.go"),
        File(File(rcbridgeSrcDir, "envhack"), "envhack.go"),
        golang.map { it.outputs.files },
        goenv.map { it.outputs.files },
        gomobile.map { it.outputs.files },
        gowrapper.map { it.outputs.files },
    )
    inputs.properties(
        "android.defaultConfig.minSdk" to android.defaultConfig.minSdk!!,
        "android.namespace" to android.namespace!!,
        "androidComponents.sdkComponents.ndkDirectory" to
                androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath },
        "androidComponents.sdkComponents.sdkDirectory" to
                androidComponents.sdkComponents.sdkDirectory.map { it.asFile.absolutePath },
    )
    outputs.files(
        rcbridgeDir.map { it.file("rcbridge.aar") },
        rcbridgeDir.map { it.file("rcbridge-sources.jar") },
    )

    doFirst {
        tempDir.get().asFile.mkdirs()
    }

    val injected = project.objects.newInstance<InjectedExecOps>()

    doLast {
        val goExecutable = golang.get().outputs.files.find { it.name == "go" }!!
        val goBinDir = goExecutable.parentFile
        val gomobileExecutable = gomobile.get().outputs.files.find { it.name == "gomobile" }!!
        val binDir = gomobileExecutable.parentFile

        injected.execOps.exec {
            executable(gomobileExecutable)
            args(
                "bind",
                "-v",
                "-o", rcbridgeAar.get().asFile.absolutePath,
                "-target=android",
                "-androidapi=${android.defaultConfig.minSdk}",
                "-javapkg=${android.namespace}.binding",
                "-trimpath",
                ".",
            )
            environment(
                // gomobile only supports finding gobind in $PATH. binDir is listed before goBinDir
                // so that gowrapper is used.
                "PATH" to "$binDir${File.pathSeparator}$goBinDir${File.pathSeparator}${environment["PATH"]}",
                "ANDROID_HOME" to androidComponents.sdkComponents.sdkDirectory.get()
                    .asFile.absolutePath,
                "ANDROID_NDK_HOME" to androidComponents.sdkComponents.ndkDirectory.get()
                    .asFile.absolutePath,
                "TMPDIR" to tempDir.get().asFile.absolutePath,
                // The wrapper will use this as a template to construct a relative path for
                // reproducible builds. This will need to change if gomobile ever changes their
                // temp directory layout.
                "GOWRAPPER_BASE_PATH" to File(
                    File(tempDir.get().asFile, "gomobile-work-PLACEHOLDER"),
                    "src-android-PLACEHOLDER",
                ),
            )
            addGoEnvironment(this)

            workingDir(rcbridgeSrcDir)
        }

        // gomobile fails to clean up its temp directories after it switched to using go modules.
        // These directories are never reused, so delete them.
        val subDirs = tempDir.get().asFile.listFiles { _, name: String ->
            name.startsWith("gomobile-work-")
        }
        if (subDirs != null) {
            for (subDir in subDirs) {
                println("Cleaning up gomobile leftovers: $subDir")

                injected.execOps.exec {
                    executable(goExecutable)
                    args("clean", "-modcache")
                    environment("GOPATH", subDir.absolutePath)
                }

                File(subDir, "pkg").delete()
                subDir.delete()
            }
        }
    }
}

/*
 * NOTE: This requires the https://crates.io/crates/resvg CLI utility. RSAF's SVG icon uses
 * transform-origin, which very few SVG parsers support.
 *
 * https://gitlab.gnome.org/GNOME/librsvg/-/issues/685
 * https://gitlab.com/inkscape/inbox/-/issues/4640
 */
tasks.register("iconPng") {
    val inputSvg = File(File(File(rootDir, "app"), "images"), "icon.svg")
    val outputPng = File(File(File(File(rootDir, "metadata"), "en-US"), "images"), "icon.png")

    inputs.files(inputSvg)
    outputs.files(outputPng)

    val injected = project.objects.newInstance<InjectedExecOps>()

    doLast {
        injected.execOps.exec {
            executable("resvg")
            args("-w", "512", "-h", "512", inputSvg, outputPng)
        }
    }
}

androidComponents.onVariants { variant ->
    variant.lifecycleTasks.registerPreBuild(archive)
    variant.lifecycleTasks.registerPreBuild(rcbridge)
}

data class LinkRef(val type: String, val number: Int) : Comparable<LinkRef> {
    override fun compareTo(other: LinkRef): Int = compareValuesBy(
        this,
        other,
        { it.type },
        { it.number },
    )

    override fun toString(): String = "[$type #$number]"
}

fun checkBrackets(line: String) {
    var expectOpening = true

    for (c in line) {
        if (c == '[' || c == ']') {
            if (c == '[' != expectOpening) {
                throw IllegalArgumentException("Mismatched brackets: $line")
            }

            expectOpening = !expectOpening
        }
    }

    if (!expectOpening) {
        throw IllegalArgumentException("Missing closing bracket: $line")
    }
}

fun updateChangelogLinks(baseUrl: String) {
    val file = File(rootDir, "CHANGELOG.md")
    val regexStandaloneLink = Regex("\\[([^\\]]+)\\](?![\\(\\[])")
    val regexAutoLink = Regex("(Issue|PR) #(\\d+)")
    val links = hashMapOf<LinkRef, String>()
    var skipRemaining = false
    val changelog = mutableListOf<String>()

    file.useLines { lines ->
        for (rawLine in lines) {
            val line = rawLine.trimEnd()

            if (!skipRemaining) {
                checkBrackets(line)
                val matches = regexStandaloneLink.findAll(line)

                for (linkMatch in matches) {
                    val linkText = linkMatch.groupValues[1]
                    val match = regexAutoLink.matchEntire(linkText)
                    require(match != null) { "Invalid link format: $linkText" }

                    val type = match.groupValues[1]
                    val number = match.groupValues[2].toInt()

                    val link = when (type) {
                        "Issue" -> "$baseUrl/issues/$number"
                        "PR" -> "$baseUrl/pull/$number"
                        else -> throw IllegalArgumentException("Unknown link type: $type")
                    }

                    // #0 is used for examples only
                    if (number != 0) {
                        links[LinkRef(type, number)] = link
                    }
                }

                if ("Do not manually edit the lines below" in line) {
                    skipRemaining = true
                }

                changelog.add(line)
            }
        }
    }

    for ((ref, link) in links.entries.sortedBy { it.key }) {
        changelog.add("$ref: $link")
    }

    changelog.add("")

    file.writeText(changelog.joinToString("\n"))
}

fun updateChangelog(version: String?, replaceFirst: Boolean) {
    val file = File(rootDir, "CHANGELOG.md")
    val expected = if (version != null) { "### Version $version" } else { "### Unreleased" }

    val changelog = mutableListOf<String>().apply {
        // This preserves a trailing newline, unlike File.readLines()
        addAll(file.readText().lineSequence())
    }

    val index = changelog.indexOfFirst { it.startsWith("### ") }
    if (index == -1) {
        changelog.addAll(0, listOf(expected, ""))
    } else if (changelog[index] != expected) {
        if (replaceFirst) {
            changelog[index] = expected
        } else {
            changelog.addAll(index, listOf(expected, ""))
        }
    }

    file.writeText(changelog.joinToString("\n"))
}

tasks.register("changelogUpdateLinks") {
    doLast {
        updateChangelogLinks(projectUrl)
    }
}

tasks.register("changelogPreRelease") {
    val version = project.findProperty("releaseVersion")

    doLast {
        updateChangelog(version!!.toString(), true)
    }
}

tasks.register("changelogPostRelease") {
    doLast {
        updateChangelog(null, false)
    }
}

tasks.register("versionPreRelease") {
    // This needs to be computed manually since the git tag hasn't been created yet at this point.
    val gitVersionCode = project.findProperty("releaseVersion")
        ?.let { getVersionCode(VersionTriple("v$it", 0, ObjectId.zeroId())) }

    doLast {
        File(File(rootDir, "metadata"), "version.txt").writeText(gitVersionCode!!.toString())
    }
}

tasks.register("preRelease") {
    dependsOn("changelogUpdateLinks")
    dependsOn("changelogPreRelease")
    dependsOn("versionPreRelease")
}

tasks.register("postRelease") {
    dependsOn("changelogPostRelease")
}

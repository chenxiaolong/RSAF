@file:Suppress("UnstableApiUsage")

import org.eclipse.jgit.api.ArchiveCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.archive.TarFormat
import org.eclipse.jgit.lib.ObjectId
import org.jetbrains.kotlin.backend.common.pop

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("de.undercouch.download") version "5.3.1"
}

buildscript {
    dependencies {
        classpath("org.eclipse.jgit:org.eclipse.jgit:6.3.0.202209071007-r")
        classpath("org.eclipse.jgit:org.eclipse.jgit.archive:6.3.0.202209071007-r")
    }
}

typealias VersionTriple = Triple<String?, Int, ObjectId>

fun describeVersion(git: Git): VersionTriple {
    // jgit doesn't provide a nice way to get strongly-typed objects from its `describe` command
    val describeStr = git.describe().setLong(true).call()

    return if (describeStr != null) {
        val pieces = describeStr.split('-').toMutableList()
        val commit = git.repository.resolve(pieces.pop().substring(1))
        val count = pieces.pop().toInt()
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
val releaseMetadataBranch = "master"

val extraDir = File(buildDir, "extra")
val archiveDir = File(extraDir, "archive")
val rcbridgeDir = File(extraDir, "rcbridge")
val rcbridgeAar = File(rcbridgeDir, "rcbridge.aar")

android {
    namespace = "com.chiller3.rsaf"

    compileSdk = 33
    buildToolsVersion = "33.0.2"
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.chiller3.rsaf"
        minSdk = 28
        targetSdk = 33
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
    sourceSets {
        getByName("main") {
            assets {
                srcDir(archiveDir)
            }
        }
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
    applicationVariants.all {
        // This is set here so that applicationIdSuffix will be respected
        resValue("string", "documents_authority", "$applicationId.documents")
    }
    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_11)
        targetCompatibility(JavaVersion.VERSION_11)
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    splits {
        // Split by ABI because compiled golang code is huge and a universal APK is nearly 200 MiB
        abi {
            isEnable = true
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.7.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.5.7")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.9.0")
    implementation(files(rcbridgeAar))
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

val archive = tasks.register("archive") {
    inputs.property("gitVersionTriple.third", gitVersionTriple.third)

    val outputFile = File(archiveDir, "archive.tar")
    outputs.file(outputFile)

    doLast {
        val format = "tar_${Thread.currentThread().id}"

        ArchiveCommand.registerFormat(format, TarFormat())
        try {
            outputFile.outputStream().use {
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

val rcbridge = tasks.register<Exec>("rcbridge") {
    val rcbridgeSrcDir = File(rootDir, "rcbridge")
    val tempDir = File(rcbridgeDir, "temp")

    inputs.files(
        File(rcbridgeSrcDir, "go.mod"),
        File(rcbridgeSrcDir, "go.sum"),
        File(rcbridgeSrcDir, "rcbridge.go"),
    )
    inputs.properties(
        "android.defaultConfig.minSdk" to android.defaultConfig.minSdk,
        "android.namespace" to android.namespace,
        "android.ndkDirectory" to android.ndkDirectory,
    )
    outputs.files(
        File(rcbridgeDir, "rcbridge.aar"),
        File(rcbridgeDir, "rcbridge-sources.jar")
    )

    executable = "gomobile"
    setArgs(listOf(
        "bind",
        "-v",
        "-o", rcbridgeAar,
        "-target=android",
        "-androidapi=${android.defaultConfig.minSdk}",
        "-javapkg=${android.namespace}.binding",
        ".",
    ))
    environment(
        "ANDROID_HOME" to android.sdkDirectory,
        "ANDROID_NDK_HOME" to android.ndkDirectory,
        "TMPDIR" to tempDir,
    )

    if (!environment.containsKey("GOPROXY")) {
        environment("GOPROXY", "https://proxy.golang.org,direct")
    }

    workingDir(rcbridgeSrcDir)

    doFirst {
        tempDir.mkdirs()
    }

    // gomobile fails to clean up its temp directories after it switched to using go modules. These
    // directories are never reused, so delete them.
    doLast {
        val subDirs = tempDir.listFiles { _, name: String ->
            name.startsWith("gomobile-work-")
        }
        if (subDirs != null) {
            for (subDir in subDirs) {
                println("Cleaning up gomobile leftovers: $subDir")

                exec {
                    executable("go")
                    args = listOf("clean", "-modcache")
                    environment("GOPATH", subDir.absolutePath)
                }

                File(subDir, "pkg").delete()
                subDir.delete()
            }
        }
    }
}

android.applicationVariants.all {
    preBuildProvider.configure {
        dependsOn(archive)
        dependsOn(rcbridge)
    }
}

data class LinkRef(val type: String, val number: Int, val user: String?) : Comparable<LinkRef> {
    override fun compareTo(other: LinkRef): Int = compareValuesBy(
        this,
        other,
        { it.type },
        { it.number },
        { it.user },
    )

    override fun toString(): String = buildString {
        append('[')
        append(type)
        append(" #")
        append(number)
        if (user != null) {
            append(" @")
            append(user)
        }
        append(']')
    }
}

fun updateChangelogLinks(baseUrl: String) {
    val file = File(rootDir, "CHANGELOG.md")
    val regex = Regex("\\[(Issue|PR) #(\\d+)(?: @([\\w-]+))?\\]")
    val links = hashMapOf<LinkRef, String>()
    var skipRemaining = false
    val changelog = mutableListOf<String>()

    file.useLines { lines ->
        for (rawLine in lines) {
            val line = rawLine.trimEnd()

            if (!skipRemaining) {
                val matches = regex.findAll(line)

                for (match in matches) {
                    val ref = match.groupValues[0]
                    val type = match.groupValues[1]
                    val number = match.groupValues[2].toInt()
                    val user = match.groups[3]?.value

                    val link = when (type) {
                        "Issue" -> {
                            require(user == null) { "$ref should not have a username" }
                            "$baseUrl/issues/$number"
                        }
                        "PR" -> {
                            require(user != null) { "$ref should have a username" }
                            "$baseUrl/pull/$number"
                        }
                        else -> throw IllegalArgumentException("Unknown link type: $type")
                    }

                    // #0 is used for examples only
                    if (number != 0) {
                        links[LinkRef(type, number, user)] = link
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
    doLast {
        val version = project.property("releaseVersion")

        updateChangelog(version.toString(), true)
    }
}

tasks.register("changelogPostRelease") {
    doLast {
        updateChangelog(null, false)
    }
}

tasks.register("preRelease") {
    dependsOn("changelogUpdateLinks")
    dependsOn("changelogPreRelease")
}

tasks.register("postRelease") {
    dependsOn("changelogPostRelease")
}

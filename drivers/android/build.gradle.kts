plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "me.uzaaft.bombadil"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    // The example driver; see `examples/counter.spec.ts`.
    mainClass.set("me.uzaaft.bombadil.example.CounterDriverKt")
}

// The on-device helper the adb driver talks to (`android-helper/`). It's
// Kotlin compiled against android.jar (in its own source set), shrunk
// together with the Kotlin standard library and packaged by
// `android-helper/build.sh` with the SDK's build tools, so it needs no
// Android Gradle plugin; the driver carries the APK as a resource and
// installs it on each device.
val androidHome = providers.environmentVariable("ANDROID_HOME")

val helper: SourceSet = sourceSets.create("helper") {
    kotlin.srcDirs("android-helper/src/main/kotlin", "android-helper/src/common/kotlin")
}

dependencies {
    "helperCompileOnly"(files(androidHome.map { "$it/platforms/android-35/android.jar" }))
}

tasks.named<JavaCompile>("compileHelperJava") { options.release = 11 }

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileHelperKotlin") {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        // android.jar provides the Java library, as on the device.
        noJdk = true
    }
}

val helperApk by tasks.registering(Exec::class) {
    val classes = helper.output.classesDirs
    val libraries = configurations["helperRuntimeClasspath"]
    val work = layout.buildDirectory.dir("helper/work")
    val apk = layout.buildDirectory.file("helper/apk/me/uzaaft/bombadil/android/bombadil-helper.apk")
    inputs.files(classes, libraries)
    inputs.files("android-helper/AndroidManifest.xml", "android-helper/build.sh", "android-helper/rules.pro")
    outputs.dir(layout.buildDirectory.dir("helper/apk"))
    argumentProviders.add {
        listOf("android-helper/build.sh", work.get().asFile.path, apk.get().asFile.path) +
            (classes.files + libraries.files).map { it.path }
    }
    executable = "bash"
}

// The in-process agent (`android-agent/`): Kotlin against android.jar and
// the Compose version Now in Android ships, dexed without any library
// (the app provides Compose and the Kotlin standard library at run time).
// `android-agent/package.sh` turns the dex into an APK for one app.
val agent: SourceSet = sourceSets.create("agent") {
    kotlin.srcDirs("android-agent/src/main/kotlin", "android-helper/src/common/kotlin")
}

repositories {
    google()
}

val composeVersion = "1.11.0-rc01"
val agentAars: Configuration by configurations.creating { isTransitive = false }

dependencies {
    for (module in listOf("ui", "ui-geometry", "ui-text", "ui-unit", "ui-graphics")) {
        agentAars("androidx.compose.ui:$module-android:$composeVersion@aar")
    }
    agentAars("androidx.compose.runtime:runtime-android:$composeVersion@aar")
    "agentCompileOnly"(files(androidHome.map { "$it/platforms/android-35/android.jar" }))
    "agentCompileOnly"(fileTree(layout.buildDirectory.dir("agent/aars")) { builtBy("agentAarClasses") })
    "agentCompileOnly"("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
    "agentCompileOnly"("androidx.collection:collection-jvm:1.5.0")
    "agentCompileOnly"("androidx.lifecycle:lifecycle-common-jvm:2.10.0")
}

/** The classes.jar of each AAR, as <name>.jar. */
val agentAarClasses by tasks.registering {
    val out = layout.buildDirectory.dir("agent/aars")
    inputs.files(agentAars)
    outputs.dir(out)
    doLast {
        delete(out)
        for (aar in agentAars.files) {
            copy {
                from(zipTree(aar)) { include("classes.jar") }
                into(out)
                rename { aar.nameWithoutExtension + ".jar" }
            }
        }
    }
}

tasks.named<JavaCompile>("compileAgentJava") { options.release = 11 }

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileAgentKotlin") {
    dependsOn(agentAarClasses)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        noJdk = true
    }
}

val agentDex by tasks.registering(Exec::class) {
    val classes = agent.output.classesDirs
    val out = layout.buildDirectory.dir("agent/dex")
    inputs.files(classes)
    outputs.dir(out)
    argumentProviders.add {
        listOf(
            "--release", "--min-api", "26",
            "--lib", androidHome.get() + "/platforms/android-35/android.jar",
            "--output", out.get().asFile.also { it.mkdirs() }.path,
        ) + agent.compileClasspath.files.filter { it.extension == "jar" }.flatMap { listOf("--classpath", it.path) } +
            classes.files.flatMap { dir -> dir.walk().filter { it.extension == "class" }.map { it.path }.toList() }
    }
    executable = androidHome.get() + "/build-tools/36.0.0/d8"
}

sourceSets {
    main {
        kotlin.srcDir("examples")
        resources.srcDir(helperApk.map { it.outputs.files.singleFile })
    }
    test {
        // The helper's Android-free tree code, tested on the JVM.
        kotlin.srcDir("android-helper/src/common/kotlin")
    }
}

// `bin/bombadil-android`: the adb driver for Android apps.
val androidStartScripts by tasks.registering(CreateStartScripts::class) {
    applicationName = "bombadil-android"
    mainClass.set("me.uzaaft.bombadil.android.AdbAppDriverKt")
    outputDir = layout.buildDirectory.dir("android-scripts").get().asFile
    classpath = tasks.startScripts.get().classpath
}

distributions.main {
    contents {
        into("bin") { from(androidStartScripts) }
    }
}

tasks.test {
    useJUnitPlatform()
}

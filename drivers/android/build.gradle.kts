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
// plain Java against android.jar, packaged by `android-helper/build.sh`
// with the SDK's build tools, so it needs no Android Gradle plugin; the
// driver carries the APK as a resource and installs it on each device.
val androidHome = providers.environmentVariable("ANDROID_HOME")

val compileHelper by tasks.registering(JavaCompile::class) {
    source("android-helper/src/main/java", "android-helper/src/common/java")
    classpath = files(androidHome.map { "$it/platforms/android-35/android.jar" })
    destinationDirectory = layout.buildDirectory.dir("helper/classes")
    options.release = 11
}

val helperApk by tasks.registering(Exec::class) {
    val classes = compileHelper.flatMap { it.destinationDirectory }
    val work = layout.buildDirectory.dir("helper/work")
    val apk = layout.buildDirectory.file("helper/apk/me/uzaaft/bombadil/android/bombadil-helper.apk")
    inputs.dir(classes)
    inputs.files("android-helper/AndroidManifest.xml", "android-helper/build.sh")
    outputs.dir(layout.buildDirectory.dir("helper/apk"))
    commandLine(
        "bash", "android-helper/build.sh",
        classes.get().asFile.path, work.get().asFile.path, apk.get().asFile.path,
    )
}

sourceSets {
    main {
        kotlin.srcDir("examples")
        resources.srcDir(helperApk.map { it.outputs.files.singleFile })
    }
    test {
        // The helper's Android-free tree code, tested on the JVM.
        java.srcDir("android-helper/src/common/java")
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

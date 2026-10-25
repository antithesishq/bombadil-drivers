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

sourceSets {
    main {
        kotlin.srcDir("examples")
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

package me.uzaaft.bombadil.android

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/** Runs `adb` against one device. */
class Adb(val serial: String, private val adb: String = "adb") {
    /** Runs `adb -s <serial> <args>` and returns its stdout, failing on a nonzero exit. */
    fun run(vararg args: String, timeoutSeconds: Long = 60): String =
        runBytes(*args, timeoutSeconds = timeoutSeconds).toString(Charsets.UTF_8)

    fun runBytes(vararg args: String, timeoutSeconds: Long = 60): ByteArray {
        val command = listOf(adb, "-s", serial) + args
        val process = synchronized(this) {
            check(!cancelled) { "cancelled" }
            ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.PIPE).start().also { current = it }
        }
        process.outputStream.close()
        val stderr = StringBuilder()
        val stderrReader = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }
            .apply { start() }
        val stdout = process.inputStream.readBytes()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}")
        }
        stderrReader.join()
        check(!cancelled) { "cancelled" }
        check(process.exitValue() == 0) {
            "`${command.joinToString(" ")}` exited with ${process.exitValue()}: $stderr${stdout.toString(Charsets.UTF_8)}".trim()
        }
        return stdout
    }

    @Volatile private var cancelled = false
    private var current: Process? = null

    /** Kills the command in progress and makes later ones fail, until [resume]. */
    @Synchronized fun cancel() {
        cancelled = true
        current?.destroyForcibly()
    }

    @Synchronized fun resume() {
        cancelled = false
    }

    /** Runs [command] in the device's shell, without a terminal, and returns its output. */
    fun shell(command: String, timeoutSeconds: Long = 60) = run("exec-out", command, timeoutSeconds = timeoutSeconds)

    companion object {
        /**
         * Claims the first of [serials] that no other process on this host
         * has claimed, so parallel drivers each get their own device. The
         * claim lasts until this process exits.
         */
        fun claim(serials: List<String>): Adb {
            val directory = File(System.getProperty("java.io.tmpdir"), "bombadil-adb-locks").apply { mkdirs() }
            for (serial in serials) {
                val channel = FileChannel.open(
                    File(directory, serial).toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
                val lock: FileLock? = channel.tryLock()
                if (lock != null) {
                    heldLocks += lock
                    return Adb(serial)
                }
                channel.close()
            }
            error("all devices are in use by other drivers: ${serials.joinToString()}")
        }

        private val heldLocks = mutableListOf<FileLock>()
    }
}

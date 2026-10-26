package me.uzaaft.bombadil.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.Writer
import java.net.Socket
import java.security.MessageDigest

/**
 * Talks to the on-device helper (`android-helper/`): an instrumentation
 * that keeps a UiAutomation connection open and answers line-delimited
 * JSON requests on the abstract socket `bombadil-helper`, reached through
 * `adb forward`.
 *
 * Requests are objects with a `cmd`; every response has `ok`, and `error`
 * when it's false:
 * - `ping` → `apkSha256` of the running helper.
 * - `tree` → `state` (focused package, activity and window title, and the
 *   pruned node tree of every interactive window), `buildMs`.
 * - `settle` `quietMs` `timeoutMs` → `state`, `stable`, `trees`: waits for
 *   `quietMs` without accessibility events and fetches the tree, until two
 *   trees agree. For a session's first state.
 * - `next` `gapMs` `foreignGapMs` `appPackage` `frameTimeoutMs` `windowTimeoutMs` `drainMaxMs` →
 *   `state`, `signal` (`frame`, `activity`, `window`, `timeout`, `windowCap`,
 *   `drainCap`), `waitMs`, `firstFrameMs`, `frames`, `buildMs`: after an
 *   action, waits for the app's response (frames presented, or an activity or window
 *   change) and returns the tree, best effort.
 * - `click`/`longClick` `node`, `setText` `node`? `text` `replace`?,
 *   `scroll` `node` `direction` → `done`, then `via` (`action` or
 *   `input`) or `reason`: an accessibility action on the node from the
 *   last `tree`, falling back to injected touches at its centre.
 * - `tap` `x` `y`, `swipe` `x1` `y1` `x2` `y2` `durationMs`, `key`
 *   `code`, `global` `action` (`back`, `home`).
 *
 * The helper outlives sessions: [connect] reuses one that's running the
 * same APK, and otherwise installs and starts it.
 */
class AndroidHelper(private val adb: Adb) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: Writer? = null

    @Volatile private var cancelled = false

    /** Size of the last response, and how long parsing it took, for timings. */
    var lastResponseBytes = 0
        private set
    var lastParseMs = 0.0
        private set

    /** Connects to the helper, installing and starting it first if needed. */
    fun connect() {
        val port = forward()
        if (open(port) && runCatching { ping() }.getOrNull() == apkSha256) return
        close()
        install()
        // `am instrument -w` holds the UiAutomation connection, so it must
        // outlive this command. It does under `adb shell`, not `exec-out`.
        adb.run("shell", "nohup am instrument -w $PACKAGE/.Helper >/dev/null 2>&1 &")
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            check(!cancelled) { "cancelled" }
            if (open(port) && runCatching { ping() }.getOrNull() == apkSha256) return
            close()
            check(System.currentTimeMillis() < deadline) { "the helper didn't start on ${adb.serial}" }
            Thread.sleep(200)
        }
    }

    /** Sends [request] and returns the response, failing if it isn't `ok`. */
    fun request(request: JsonObject): JsonObject {
        check(!cancelled) { "cancelled" }
        val writer = writer ?: error("not connected to the helper")
        writer.write(request.toString())
        writer.write("\n")
        writer.flush()
        val line = reader!!.readLine() ?: error("the helper closed the connection")
        val parsing = System.nanoTime()
        val response = Json.parseToJsonElement(line) as JsonObject
        lastResponseBytes = line.length
        lastParseMs = (System.nanoTime() - parsing) / 1_000_000.0
        check(response["ok"] == JsonPrimitive(true)) { "helper failed $request: ${response["error"]}" }
        return response
    }

    fun request(cmd: String, vararg args: Pair<String, Any?>) = request(
        buildJsonObject {
            put("cmd", JsonPrimitive(cmd))
            for ((key, value) in args) {
                when (value) {
                    null -> {}
                    is Number -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, JsonPrimitive(value))
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        },
    )

    /** Aborts the request in progress, from another thread, and makes later ones fail. */
    fun cancel() {
        cancelled = true
        close()
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }

    private fun ping() = request("ping")["apkSha256"]?.jsonPrimitive?.content

    private fun open(port: Int): Boolean {
        close()
        return runCatching {
            // adb accepts the connection even when nothing listens on the
            // device, so only a ping tells whether the helper is there.
            val s = Socket("127.0.0.1", port).apply { soTimeout = 30_000 }
            socket = s
            reader = s.getInputStream().bufferedReader()
            writer = s.getOutputStream().bufferedWriter()
        }.isSuccess
    }

    /** The host port forwarded to the helper's socket, set up if needed. */
    private fun forward(): Int {
        val existing = adb.run("forward", "--list").lines().map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 3 && it[0] == adb.serial && it[2] == "localabstract:$SOCKET" }
        if (existing != null) return existing[1].removePrefix("tcp:").toInt()
        return adb.run("forward", "tcp:0", "localabstract:$SOCKET").trim().toInt()
    }

    private fun install() {
        val file = File.createTempFile("bombadil-helper", ".apk")
        try {
            file.writeBytes(apk)
            try {
                adb.run("install", "-r", file.path, timeoutSeconds = 120)
            } catch (e: IllegalStateException) {
                // Signed with another build's key: start over.
                if ("INSTALL_FAILED_UPDATE_INCOMPATIBLE" !in e.message.orEmpty()) throw e
                adb.run("uninstall", PACKAGE)
                adb.run("install", file.path, timeoutSeconds = 120)
            }
        } finally {
            file.delete()
        }
    }

    companion object {
        const val PACKAGE = "me.uzaaft.bombadil.helper"
        const val SOCKET = "bombadil-helper"

        val apk: ByteArray by lazy {
            AndroidHelper::class.java.getResourceAsStream("bombadil-helper.apk")?.use { it.readBytes() }
                ?: error("bombadil-helper.apk is missing from the classpath; build with `gradle installDist`")
        }

        val apkSha256: String by lazy {
            MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        }
    }
}

package me.uzaaft.bombadil.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedReader
import java.io.Writer
import java.net.Socket

/**
 * A line-delimited JSON connection to an abstract socket on the device,
 * reached through `adb forward`: requests are objects with a `cmd`, and
 * every response has `ok`, and `error` when it's false.
 */
open class DeviceSocket(protected val adb: Adb, private val socketName: String) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: Writer? = null

    @Volatile private var cancelled = false

    /** Size of the last response, and how long parsing it took, for timings. */
    var lastResponseBytes = 0
        private set
    var lastParseMs = 0.0
        private set

    val connected get() = writer != null

    /** Sends [request] and returns the response, failing if it isn't `ok`. */
    fun request(request: JsonObject): JsonObject {
        check(!cancelled) { "cancelled" }
        val writer = writer ?: error("not connected to $socketName")
        writer.write(request.toString())
        writer.write("\n")
        writer.flush()
        val line = reader!!.readLine() ?: run {
            close()
            error("$socketName closed the connection")
        }
        val parsing = System.nanoTime()
        val response = Json.parseToJsonElement(line) as JsonObject
        lastResponseBytes = line.length
        lastParseMs = (System.nanoTime() - parsing) / 1_000_000.0
        check(response["ok"] == JsonPrimitive(true)) { "$socketName failed $request: ${response["error"]}" }
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

    /** Connects to [port], returning whether that worked; only a request tells whether anything listens. */
    protected fun open(port: Int): Boolean {
        close()
        return runCatching {
            // adb accepts the connection even when nothing listens on the
            // device.
            val s = Socket("127.0.0.1", port).apply { soTimeout = 30_000 }
            socket = s
            reader = s.getInputStream().bufferedReader()
            writer = s.getOutputStream().bufferedWriter()
        }.isSuccess
    }

    /** The host port forwarded to the socket, set up if needed. */
    protected fun forward(): Int {
        val existing = adb.run("forward", "--list").lines().map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 3 && it[0] == adb.serial && it[2] == "localabstract:$socketName" }
        if (existing != null) return existing[1].removePrefix("tcp:").toInt()
        return adb.run("forward", "tcp:0", "localabstract:$socketName").trim().toInt()
    }

    /** Connects to the socket, waiting up to [timeoutMs] until [ready] answers true. */
    protected fun await(timeoutMs: Long, what: String, ready: () -> Boolean) {
        val port = forward()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            check(!cancelled) { "cancelled" }
            if (open(port) && runCatching(ready).getOrDefault(false)) return
            close()
            check(System.currentTimeMillis() < deadline) { "$what didn't start on ${adb.serial}" }
            Thread.sleep(100)
        }
    }
}

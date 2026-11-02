package me.uzaaft.bombadil

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Version of Bombadil's external driver protocol this library speaks. */
const val PROTOCOL_VERSION = 0

/**
 * Drives one app session for Bombadil.
 *
 * States and actions are JSON values whose shape the driver and the
 * specification agree on.
 */
interface BombadilDriver {
    /** Name reported to Bombadil in the handshake. */
    val name: String

    /**
     * How long Bombadil waits for each `state` (including the first one,
     * after [start]) before considering the driver stalled, or null for
     * Bombadil's default of 30 seconds.
     */
    val stallTimeoutMs: Long? get() = null

    /** Launches the app and returns its first settled state. */
    fun start(): JsonElement

    /** Performs [action] and returns the state the app settles into. */
    fun apply(action: JsonElement): JsonElement

    fun screenshotPng(): ByteArray? = null

    /** Shuts the app down. */
    fun stop()

    /**
     * Called from another thread when Bombadil asks the driver to stop
     * while [start] or [apply] may still be running. Drivers whose actions
     * take a while should make those calls return or throw soon after;
     * errors after this are not reported. [stop] is called afterwards.
     */
    fun cancel() {}
}

/** Messages Bombadil sends to the driver. */
sealed interface BombadilMessage {
    data class Apply(val action: JsonElement) : BombadilMessage

    data object Terminate : BombadilMessage

    companion object {
        fun parse(line: String): BombadilMessage {
            val message = Json.parseToJsonElement(line).jsonObject
            return when (val type = message["type"]?.jsonPrimitive?.content) {
                "apply" -> Apply(message["action"] ?: error("`apply` without `action`"))
                "terminate" -> Terminate
                else -> error("unknown message type `$type`")
            }
        }
    }
}

/**
 * Runs [driver] for one Bombadil session, reading Bombadil's messages
 * with [readLine] and writing the driver's with [writeLine] (stdin and
 * stdout by default), until Bombadil terminates it or closes the input.
 *
 * Errors from the driver are reported to Bombadil and rethrown. Log to
 * stderr: stdout carries the protocol.
 */
fun runDriver(
    driver: BombadilDriver,
    readLine: () -> String? = ::readlnOrNull,
    writeLine: (String) -> Unit = { println(it); System.out.flush() },
) {
    fun send(message: JsonObject) = writeLine(message.toString())
    fun state(state: JsonElement) = send(buildJsonObject {
        put("type", "state")
        put("state", state)
        driver.screenshotPng()?.let { put("screenshot_png", Base64.getEncoder().encodeToString(it)) }
    })

    send(buildJsonObject {
        put("type", "hello")
        put("protocol", PROTOCOL_VERSION)
        put("name", driver.name)
        driver.stallTimeoutMs?.let { put("stall_timeout_ms", it) }
    })
    // Read on another thread, so that a `terminate` can cancel the action
    // in progress instead of waiting for it (Bombadil kills drivers that
    // take more than 5 seconds to exit). A null in the queue stands for
    // the end of the input.
    val messages = LinkedBlockingQueue<Result<BombadilMessage?>>()
    val terminating = AtomicBoolean(false)
    thread(isDaemon = true, name = "bombadil-reader") {
        while (true) {
            val message = runCatching {
                var line: String?
                do line = readLine() while (line != null && line.isBlank())
                line?.let(BombadilMessage::parse)
            }
            if (message.getOrNull() == BombadilMessage.Terminate) {
                terminating.set(true)
                driver.cancel()
            }
            messages.put(message)
            if (message.getOrNull() !is BombadilMessage.Apply) break
        }
    }
    try {
        state(driver.start())
        while (true) {
            when (val message = messages.take().getOrThrow()) {
                is BombadilMessage.Apply -> state(driver.apply(message.action))
                BombadilMessage.Terminate, null -> return
            }
        }
    } catch (error: Exception) {
        if (terminating.get()) return

        send(buildJsonObject {
            put("type", "error")
            put("message", JsonPrimitive(error.toString()))
        })
        throw error
    } finally {
        driver.stop()
    }
}

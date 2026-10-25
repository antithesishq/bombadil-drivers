package me.uzaaft.bombadil

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

    /** Launches the app and returns its first settled state. */
    fun start(): JsonElement

    /** Performs [action] and returns the state the app settles into. */
    fun apply(action: JsonElement): JsonElement

    /** Shuts the app down. */
    fun stop()
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
    })

    send(buildJsonObject {
        put("type", "hello")
        put("protocol", PROTOCOL_VERSION)
        put("name", driver.name)
    })
    try {
        state(driver.start())
        while (true) {
            val line = readLine() ?: return
            if (line.isBlank()) continue
            when (val message = BombadilMessage.parse(line)) {
                is BombadilMessage.Apply -> state(driver.apply(message.action))
                BombadilMessage.Terminate -> return
            }
        }
    } catch (error: Exception) {
        send(buildJsonObject {
            put("type", "error")
            put("message", JsonPrimitive(error.toString()))
        })
        throw error
    } finally {
        driver.stop()
    }
}

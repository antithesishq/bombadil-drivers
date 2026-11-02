package me.uzaaft.bombadil.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbAppDriverTest {
    @Test
    fun helperSignalsAndSkippedActionsBecomeMetadataWithoutChangingState() {
        Fixture().use { fixture ->
            var stable = false
            var signal = "frame"
            var done: Boolean? = true
            fixture.helper.reply = { command -> when (command) {
                "settle" -> """{"ok":true,"stable":$stable,"state":${fixture.appState}}"""
                "next" -> """{"ok":true,"signal":"$signal","state":${fixture.appState}}"""
                else -> """{"ok":true,"done":$done,"reason":"refused by device"}"""
            } }
            val driver = fixture.driver()
            try {
                val before = System.currentTimeMillis() * 1_000
                val initial = driver.start()
                assertFalse(initial.settled)
                assertNull(initial.notApplied)
                assertEquals(JsonObject(fixture.appState + mapOf("lastAction" to JsonNull, "blockedIntents" to Json.parseToJsonElement("[]"))), initial.state)
                assertTrue(initial.timestampUs >= before)
                var previous = initial.timestampUs
                for (next in listOf("frame", "activity", "window", "timeout", "windowCap", "drainCap", "unknown")) {
                    signal = next
                    val result = driver.apply(JsonPrimitive("Back"))
                    assertEquals(next in setOf("frame", "activity", "window"), result.settled, next)
                    assertNull(result.notApplied)
                    assertTrue(result.timestampUs >= previous)
                    assertTrue(result.timestampUs <= System.currentTimeMillis() * 1_000 + 999)
                    previous = result.timestampUs
                }
                signal = "frame"
                done = false
                assertEquals("refused by device", driver.apply(JsonPrimitive("Back")).notApplied)
                done = null
                assertNull(driver.apply(JsonPrimitive("Back")).notApplied)
                val skipped = driver.apply(Json.parseToJsonElement("""{"Tap":{"text":"missing"}}"""))
                assertTrue(skipped.notApplied != null)
                assertTrue(skipped.settled)
                stable = true
                assertTrue(driver.start().settled)
            } finally {
                driver.stop()
            }
        }
    }

    @Test
    fun agentSettlingAndDisconnectionUseTheResponseThatSuppliedTheState() {
        Fixture().use { fixture ->
            fixture.helper.reply = { """{"ok":true,"stable":true,"state":${fixture.appState}}""" }
            var settled = false
            var disconnect = false
            fixture.agent.reply = {
                if (disconnect) null else """{"ok":true,"done":true,"settled":$settled,"focused":true,"state":${fixture.appState},"blocked":[{"action":"view"}]}"""
            }
            val driver = fixture.driver(withAgent = true)
            try {
                val initial = driver.start()
                assertFalse(initial.settled) // Agent cap overrides the helper's stable tree.
                assertNull(initial.notApplied)
                assertEquals(Json.parseToJsonElement("""[{"action":"view"}]"""), initial.state.jsonObject["blockedIntents"])
                settled = true
                assertTrue(driver.apply(JsonPrimitive("Back")).settled)
                settled = false
                assertFalse(driver.apply(JsonPrimitive("Back")).settled)
                disconnect = true
                val fallback = driver.apply(JsonPrimitive("Back"))
                assertTrue(fallback.settled) // The helper's newly captured state is stable.
                assertNull(fallback.notApplied) // The connection can close after the action applied.
                assertTrue(fallback.timestampUs >= initial.timestampUs)
            } finally {
                driver.stop()
            }
        }
    }

    @Test
    fun leavingAgentUsesHelperCapAndPreservesActionRefusal() {
        Fixture().use { fixture ->
            fixture.helper.reply = { command ->
                if (command == "await") """{"ok":true,"signal":"windowCap","state":{"package":"other","windows":[]}}"""
                else """{"ok":true,"stable":true,"state":${fixture.appState}}"""
            }
            fixture.agent.reply = { command ->
                if (command == "state") """{"ok":true,"settled":true,"focused":true,"top":"app/Main","state":${fixture.appState}}"""
                else """{"ok":true,"done":false,"reason":"refused","leaving":true,"from":"app/Main"}"""
            }
            val driver = fixture.driver(withAgent = true)
            try {
                driver.start()
                val result = driver.apply(JsonPrimitive("Back"))
                assertFalse(result.settled)
                assertEquals("refused", result.notApplied)
                assertEquals(JsonPrimitive("other"), result.state.jsonObject["package"])
            } finally {
                driver.stop()
            }
        }
    }

    @Test
    fun fallbackTimestampBelongsToHelperCaptureNotLaterAgentAttempt() {
        Fixture().use { fixture ->
            fixture.helper.reply = { """{"ok":true,"stable":true,"state":${fixture.appState}}""" }
            var afterHelperCapture = 0L
            fixture.agent.reply = {
                Thread.sleep(20)
                afterHelperCapture = System.currentTimeMillis() * 1_000
                """{"ok":true,"settled":false,"focused":false,"state":${fixture.appState}}"""
            }
            val driver = fixture.driver(withAgent = true)
            try {
                val result = driver.start()
                assertTrue(result.settled)
                assertTrue(result.timestampUs < afterHelperCapture)
            } finally {
                driver.stop()
            }
        }
    }

    /** Exercises the public driver over real host sockets and an adb command stub. */
    private class Fixture : AutoCloseable {
        val appState = Json.parseToJsonElement("""{"package":"app","windows":[]}""").jsonObject
        val helper = Responder(helper = true)
        val agent = Responder(helper = false)
        private val directory = Files.createTempDirectory("bombadil-driver-test").toFile()
        private val adb = File(directory, "adb").apply {
            writeText("""
                |#!/bin/sh
                |case "${'$'}*" in
                |  *"forward --list"*)
                |    echo "test tcp:${helper.port} localabstract:bombadil-helper"
                |    echo "test tcp:${agent.port} localabstract:bombadil-agent" ;;
                |  *"getprop sys.boot_completed"*) echo 1 ;;
                |  *"pm clear app"*) echo Success ;;
                |  *"resolve-activity"*) echo app/Main ;;
                |esac
                |exit 0
            """.trimMargin() + "\n")
            setExecutable(true)
        }

        fun driver(withAgent: Boolean = false) = AdbAppDriver(
            Adb("test", adb.absolutePath), "app",
            agentApk = if (withAgent) File(directory, "agent.apk").apply { writeText("test") } else null,
        )

        override fun close() {
            helper.close()
            agent.close()
            directory.deleteRecursively()
        }
    }

    private class Responder(private val helper: Boolean) : AutoCloseable {
        private val server = ServerSocket(0)
        val port get() = server.localPort
        @Volatile var reply: (String) -> String? = { error("unexpected $it") }
        private val worker = thread(isDaemon = true) {
            runCatching {
                while (!server.isClosed) server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream().bufferedWriter()
                    while (true) {
                        val line = input.readLine() ?: break
                        val command = Json.parseToJsonElement(line).jsonObject.getValue("cmd").jsonPrimitive.content
                        val response = if (command == "ping") {
                            if (helper) """{"ok":true,"apkSha256":"${AndroidHelper.apkSha256}"}""" else """{"ok":true}"""
                        } else reply(command) ?: break
                        output.write(response + "\n")
                        output.flush()
                    }
                }
            }
        }

        override fun close() {
            server.close()
            worker.join(1_000)
        }
    }
}

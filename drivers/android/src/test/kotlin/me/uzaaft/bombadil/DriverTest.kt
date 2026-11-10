package me.uzaaft.bombadil

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeDriver : BombadilDriver {
    val applied = mutableListOf<JsonElement>()
    var stopped = false

    override val name = "fake"

    override fun start() = StateResult(buildJsonObject { put("applied", 0) }, timestampUs = 1_000_000)

    override fun apply(action: JsonElement): StateResult {
        if (action == JsonPrimitive("Crash")) error("crashed")
        applied += action
        return StateResult(buildJsonObject { put("applied", applied.size) }, timestampUs = 1_000_000L + applied.size)
    }

    override fun stop() {
        stopped = true
    }
}

class DriverTest {
    private fun session(driver: BombadilDriver, input: List<String>, output: MutableList<String>) {
        val lines = input.iterator()
        runDriver(driver, { if (lines.hasNext()) lines.next() else null }, { output += it })
    }

    @Test
    fun attachesScreenshotsToTheirStates() {
        val fake = FakeDriver()
        val driver = object : BombadilDriver by fake {
            override fun screenshotPng() = byteArrayOf(137.toByte(), 80, fake.applied.size.toByte(), 255.toByte())
        }
        val output = mutableListOf<String>()
        session(driver, listOf("""{"type":"apply","action":"Back"}"""), output)
        val states = output.drop(1).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("iVAA/w==", "iVAB/w=="), states.map { it["screenshot_png"]!!.jsonPrimitive.content })
        assertEquals(Json.parseToJsonElement("""{"applied":1}"""), states.last()["state"])
    }

    @Test
    fun reportsAStateForTheStartAndEveryAppliedAction() {
        val driver = FakeDriver()
        val output = mutableListOf<String>()
        session(
            driver,
            listOf(
                """{"type":"apply","action":{"Tap":{"id":"ok"}}}""",
                """{"type":"apply","action":"Back"}""",
                """{"type":"terminate"}""",
                """{"type":"apply","action":"AfterTerminate"}""",
            ),
            output,
        )
        assertEquals(
            listOf(
                """{"type":"hello","protocol":0,"name":"fake"}""",
                """{"type":"state","state":{"applied":0},"settled":true,"timestamp_us":1000000}""",
                """{"type":"state","state":{"applied":1},"settled":true,"timestamp_us":1000001}""",
                """{"type":"state","state":{"applied":2},"settled":true,"timestamp_us":1000002}""",
            ).map(Json::parseToJsonElement),
            output.map(Json::parseToJsonElement),
        )
        assertEquals(
            listOf(Json.parseToJsonElement("""{"Tap":{"id":"ok"}}"""), JsonPrimitive("Back")),
            driver.applied,
        )
        assertTrue(driver.stopped)
    }

    @Test
    fun announcesTheStallTimeoutInTheHello() {
        val driver = object : BombadilDriver by FakeDriver() {
            override val stallTimeoutMs = 120_000L
        }
        val output = mutableListOf<String>()
        session(driver, listOf("""{"type":"terminate"}"""), output)
        assertEquals(
            Json.parseToJsonElement("""{"type":"hello","protocol":0,"name":"fake","stall_timeout_ms":120000}"""),
            Json.parseToJsonElement(output.first()),
        )
    }

    @Test
    fun terminateCancelsTheActionInProgress() {
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val fake = FakeDriver()
        val driver = object : BombadilDriver by fake {
            override fun apply(action: JsonElement): StateResult {
                // Stands in for a slow action that cancelling aborts.
                check(cancelled.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "not cancelled" }
                error("aborted")
            }

            override fun cancel() = cancelled.countDown()
        }
        val output = mutableListOf<String>()
        session(driver, listOf("""{"type":"apply","action":"Slow"}""", """{"type":"terminate"}"""), output)
        // The aborted action is no error: Bombadil asked to stop.
        assertEquals(listOf("hello", "state"), output.map { Json.parseToJsonElement(it).jsonObject["type"]!!.jsonPrimitive.content })
        assertTrue(fake.stopped)
    }

    @Test
    fun reportsDriverErrorsToBombadilAndStopsTheApp() {
        val driver = FakeDriver()
        val output = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            session(driver, listOf("""{"type":"apply","action":"Crash"}"""), output)
        }
        val last = Json.parseToJsonElement(output.last()).jsonObject
        assertEquals("error", last["type"]!!.jsonPrimitive.content)
        assertTrue("crashed" in last["message"]!!.jsonPrimitive.content)
        assertTrue(driver.stopped)
    }

    @Test
    fun sendsExplicitMetadataWithoutChangingAppStateAndClampsTimestamps() {
        val appState = JsonPrimitive("unchanged")
        val driver = object : BombadilDriver by FakeDriver() {
            override fun start() = StateResult(appState, timestampUs = 123)
            override fun apply(action: JsonElement) = StateResult(appState, false, "no matching node", 100)
        }
        val output = mutableListOf<String>()
        session(driver, listOf("""{"type":"apply","action":"Back"}"""), output)
        val states = output.drop(1).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf(123L, 123L), states.map { it.getValue("timestamp_us").jsonPrimitive.long })
        assertEquals(listOf(true, false), states.map { it.getValue("settled").jsonPrimitive.boolean })
        assertTrue("not_applied" !in states.first())
        assertEquals(JsonPrimitive("no matching node"), states.last()["not_applied"])
        assertTrue(states.all { it["state"] == appState && "screenshot_png" !in it })
    }

    @Test
    fun rejectsNotAppliedOnInitialStateAndStops() {
        val fake = FakeDriver()
        val driver = object : BombadilDriver by fake {
            override fun start() = StateResult(JsonPrimitive(0), notApplied = "invalid")
        }
        val output = mutableListOf<String>()
        assertFailsWith<IllegalArgumentException> { session(driver, emptyList(), output) }
        assertEquals(listOf("hello", "error"), output.map { Json.parseToJsonElement(it).jsonObject["type"]!!.jsonPrimitive.content })
        assertTrue(fake.stopped)
    }

    @Test
    fun defaultTimestampIsHostEpochAtCaptureNotScreenshotTime() {
        val before = System.currentTimeMillis() * 1_000
        val captured = StateResult(JsonPrimitive(0))
        var screenshotTimestamp = 0L
        val driver = object : BombadilDriver by FakeDriver() {
            override fun start() = captured
            override fun screenshotPng(): ByteArray? {
                Thread.sleep(10)
                screenshotTimestamp = System.currentTimeMillis() * 1_000
                return null
            }
        }
        val output = mutableListOf<String>()
        session(driver, emptyList(), output)
        val timestamp = Json.parseToJsonElement(output.last()).jsonObject.getValue("timestamp_us").jsonPrimitive.long
        assertEquals(captured.timestampUs, timestamp)
        assertTrue(timestamp >= before)
        assertTrue(timestamp < screenshotTimestamp)
    }
}

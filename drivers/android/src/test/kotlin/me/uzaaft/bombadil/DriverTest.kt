package me.uzaaft.bombadil

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeDriver : BombadilDriver {
    val applied = mutableListOf<JsonElement>()
    var stopped = false

    override val name = "fake"

    override fun start(): JsonElement = buildJsonObject { put("applied", 0) }

    override fun apply(action: JsonElement): JsonElement {
        if (action == JsonPrimitive("Crash")) error("crashed")
        applied += action
        return buildJsonObject { put("applied", applied.size) }
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
                """{"type":"state","state":{"applied":0}}""",
                """{"type":"state","state":{"applied":1}}""",
                """{"type":"state","state":{"applied":2}}""",
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
}

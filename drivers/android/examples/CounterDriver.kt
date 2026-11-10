// A driver for a pretend counter app, with a bug: nothing caps the
// count. Run it under Bombadil with:
//
//   gradle installDist
//   bombadil external test --specification examples/counter.spec.ts \
//     -- build/install/bombadil-compose/bin/bombadil-compose
package me.uzaaft.bombadil.example

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.uzaaft.bombadil.BombadilDriver
import me.uzaaft.bombadil.StateResult
import me.uzaaft.bombadil.runDriver

class CounterDriver : BombadilDriver {
    private var count = 0

    override val name = "counter (kotlin)"

    private val state get() = buildJsonObject { put("count", count) }

    override fun start() = StateResult(state)

    override fun apply(action: JsonElement): StateResult {
        when {
            action is JsonObject && "Increment" in action ->
                count += action["Increment"]!!.jsonObject["by"]!!.jsonPrimitive.int
            action == JsonPrimitive("Reset") -> count = 0
            else -> error("unknown action: $action")
        }
        return StateResult(state)
    }

    override fun stop() {}
}

fun main() = runDriver(CounterDriver())

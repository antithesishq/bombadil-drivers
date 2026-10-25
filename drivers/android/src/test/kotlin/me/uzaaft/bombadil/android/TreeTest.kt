package me.uzaaft.bombadil.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.uzaaft.bombadil.helper.Tree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TreeTest {
    private fun node(
        bounds: List<Int>,
        text: String = "",
        desc: String = "",
        resourceId: String = "",
        clickable: Boolean = false,
        children: List<Tree.Node> = emptyList(),
    ) = Tree.Node().also {
        it.className = "android.view.View"
        it.text = text
        it.desc = desc
        it.resourceId = resourceId
        it.clickable = clickable
        it.enabled = true
        it.left = bounds[0]; it.top = bounds[1]; it.right = bounds[2]; it.bottom = bounds[3]
        it.children = children.toMutableList()
    }

    /** An Interests-like list: rows, each with an identical "Follow" button, scrolled by [dy]. */
    private fun screen(dy: Int, vararg topics: String): Tree.Window {
        val rows = topics.mapIndexed { i, topic ->
            val top = 300 + 150 * i - dy
            node(
                listOf(0, top, 1080, top + 150), clickable = true,
                children = listOf(
                    node(listOf(40, top + 50, 600, top + 100), text = topic),
                    node(listOf(900, top + 20, 1040, top + 130), desc = "Follow", clickable = true),
                ),
            )
        }
        val list = node(listOf(0, 300, 1080, 2000), resourceId = "interests:topics", children = rows)
        // A layout-only wrapper and an empty node, both pruned.
        val root = node(listOf(0, 0, 1080, 2400), children = listOf(list, node(listOf(0, 0, 0, 0), text = "gone")))
        return Tree.Window().also {
            it.type = "application"
            it.packageName = "app"
            it.root = root
            it.right = 1080; it.bottom = 2400
        }
    }

    private fun state(window: Tree.Window): JsonObject {
        Tree.build(listOf(window))
        return Json.parseToJsonElement(Tree.json("app", "app.Main", "App", listOf(window))).jsonObject
    }

    private fun JsonObject.flatten(): List<JsonObject> {
        fun walk(nodes: JsonArray): List<JsonObject> =
            nodes.flatMap { listOf(it.jsonObject) + walk(it.jsonObject.getValue("children").jsonArray) }
        return getValue("windows").jsonArray.flatMap { walk(it.jsonObject.getValue("nodes").jsonArray) }
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun prunesLabelsAndSerializes() {
        val state = state(screen(0, "Compose", "Games \"2\""))
        assertEquals("app", state.str("package"))
        val (list) = state.getValue("windows").jsonArray[0].jsonObject.getValue("nodes").jsonArray.map { it.jsonObject }
        assertEquals("interests:topics", list.str("resourceId"))
        assertEquals("View", list.str("class"))
        assertEquals("Compose Follow Games \"2\" Follow", list.str("label"))
        val labels = state.flatten().map { it.str("label") }
        assertEquals(listOf("Compose Follow Games \"2\" Follow", "Compose Follow", "Compose", "Follow", "Games \"2\" Follow", "Games \"2\"", "Follow"), labels)
    }

    @Test
    fun idsTellIdenticalButtonsApartAndSurviveScrollingAndOtherContent() {
        fun followIds(state: JsonObject) =
            state.flatten().filter { it.str("label") == "Follow" }.map { it.str("id") }

        val before = followIds(state(screen(0, "Compose", "Games")))
        assertEquals(2, before.toSet().size)
        // Scrolled, and with another row above: same ids for the same rows.
        val after = followIds(state(screen(400, "Accessibility", "Compose", "Games")))
        assertEquals(before, after.drop(1))
        assertNotEquals(after[0], after[1])
    }

    @Test
    fun numbersNodesThatLookAlike() {
        val window = screen(0, "Same", "Same")
        val ids = state(window).flatten().filter { it.str("label") == "Follow" }.map { it.str("id") }
        assertEquals(ids[0] + "-1", ids[1])
    }
}

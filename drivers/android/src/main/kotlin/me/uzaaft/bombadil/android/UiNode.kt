package me.uzaaft.bombadil.android

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** A rectangle on screen in pixels, `[left, top)` to `[right, bottom)`. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val isEmpty get() = right <= left || bottom <= top

    fun toJson() = JsonArray(listOf(left, top, right, bottom).map(::JsonPrimitive))

    companion object {
        private val pattern = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

        fun parse(text: String): Bounds {
            val (l, t, r, b) = pattern.matchEntire(text)?.destructured ?: error("bad bounds `$text`")
            return Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }
    }
}

/**
 * A visible node of the UI hierarchy, as `uiautomator dump` reports it.
 * With `testTagsAsResourceId`, Compose test tags show up as [resourceId].
 */
data class UiNode(
    val resourceId: String,
    val className: String,
    val text: String,
    val desc: String,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val focused: Boolean,
    val bounds: Bounds,
    val children: List<UiNode>,
) {
    /**
     * What a user would read on this node: its text, else its content
     * description, else its descendants' labels joined by spaces. Compose
     * puts the text of a clickable row on its children, so this is how to
     * tell such rows apart. Cut to 200 characters, so containers don't
     * repeat the whole screen.
     */
    val label: String by lazy {
        text.ifEmpty { desc }
            .ifEmpty { children.map { it.label }.filter { it.isNotEmpty() }.joinToString(" ") }
            .take(200)
    }

    /** This node and its descendants, depth first. */
    fun flatten(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { yieldAll(it.flatten()) }
    }

    private val informative
        get() = resourceId.isNotEmpty() || text.isNotEmpty() || desc.isNotEmpty() ||
            clickable || longClickable || scrollable || checkable || selected || focused

    /**
     * Drops nodes that carry nothing but layout, moving their children up,
     * and nodes with no area.
     */
    fun prune(): List<UiNode> {
        if (bounds.isEmpty) return emptyList()
        val kept = children.flatMap { it.prune() }
        return if (informative) listOf(copy(children = kept)) else kept
    }

    fun toJson(): JsonElement = buildJsonObject {
        put("resourceId", resourceId)
        put("class", className.substringAfterLast('.'))
        put("text", text)
        put("desc", desc)
        put("label", label)
        put("clickable", clickable)
        put("longClickable", longClickable)
        put("scrollable", scrollable)
        put("enabled", enabled)
        put("checkable", checkable)
        put("checked", checked)
        put("selected", selected)
        put("focused", focused)
        put("bounds", bounds.toJson())
        put("children", JsonArray(children.map { it.toJson() }))
    }

    companion object {
        /** Parses the XML that `uiautomator dump` writes, returning its root nodes. */
        fun parseDump(xml: String): List<UiNode> {
            val end = xml.lastIndexOf("</hierarchy>")
            require(end >= 0) { "not a uiautomator dump: ${xml.take(200)}" }
            val start = xml.indexOf("<?xml").coerceAtLeast(0)
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xml.substring(start, end + "</hierarchy>".length))))
            return document.documentElement.childElements().map(::fromElement)
        }

        private fun Element.childElements() =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
                .filter { it.tagName == "node" }

        private fun fromElement(e: Element): UiNode {
            fun flag(name: String) = e.getAttribute(name) == "true"
            return UiNode(
                resourceId = e.getAttribute("resource-id"),
                className = e.getAttribute("class"),
                text = e.getAttribute("text"),
                desc = e.getAttribute("content-desc"),
                clickable = flag("clickable"),
                longClickable = flag("long-clickable"),
                scrollable = flag("scrollable"),
                enabled = flag("enabled"),
                checkable = flag("checkable"),
                checked = flag("checked"),
                selected = flag("selected"),
                focused = flag("focused"),
                bounds = Bounds.parse(e.getAttribute("bounds")),
                children = e.childElements().map(::fromElement),
            )
        }
    }
}

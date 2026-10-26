package me.uzaaft.bombadil.helper

/**
 * Turns accessibility trees into the JSON the driver reports: prunes
 * layout-only nodes, labels nodes, and gives each node an id that stays
 * the same across sessions as long as the node looks the same. Kept free
 * of Android classes so it runs in the host's unit tests.
 */
object Tree {
    /** A node, filled in from an AccessibilityNodeInfo. */
    class Node {
        var resourceId = ""
        var className = ""
        var text = ""
        var desc = ""
        var clickable = false
        var longClickable = false
        var scrollable = false
        var enabled = false
        var checkable = false
        var checked = false
        var selected = false
        var focused = false
        var editable = false
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
        var children: MutableList<Node> = ArrayList()

        /** The AccessibilityNodeInfo this node came from, to act on it later. */
        var handle: Any? = null

        internal var label = ""
        internal var id = ""

        internal val isEmpty get() = right <= left || bottom <= top

        internal val informative
            get() = resourceId.isNotEmpty() || text.isNotEmpty() || desc.isNotEmpty() || clickable || longClickable ||
                scrollable || checkable || selected || focused || editable
    }

    /** A window and its (unpruned) root. */
    class Window {
        var type = ""
        var packageName = ""
        var title = ""
        var active = false
        var focused = false
        var layer = 0
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
        var root: Node? = null
        internal var nodes: List<Node> = ArrayList()
    }

    /**
     * Drops nodes that carry nothing but layout, moving their children
     * up, and nodes with no area.
     */
    private fun prune(node: Node): List<Node> {
        val kept = ArrayList<Node>()
        if (node.isEmpty) return kept
        val children = ArrayList<Node>()
        for (child in node.children) children.addAll(prune(child))
        if (node.informative) {
            node.children = children
            kept.add(node)
        } else {
            kept.addAll(children)
        }
        return kept
    }

    /**
     * What a user would read on a node: its text, else its content
     * description, else its descendants' labels joined by spaces (Compose
     * puts a clickable row's text on its children), cut to 200 characters.
     */
    private fun label(node: Node): String {
        val parts = ArrayList<String>()
        for (child in node.children) {
            val l = label(child)
            if (l.isNotEmpty()) parts.add(l)
        }
        val l = if (node.text.isNotEmpty()) node.text else if (node.desc.isNotEmpty()) node.desc else parts.joinToString(" ")
        node.label = l.take(200)
        return node.label
    }

    /**
     * Prunes and labels every window and gives every node an id, returning
     * the nodes by id. A node's id hashes its window's type and package,
     * the nearest ancestor with a resource id, its own resource id, class
     * and label, and the label of its nearest clickable ancestor (which
     * tells apart, say, the identical "Follow" buttons of different rows).
     * Not its position, so ids survive scrolling. Nodes that still look
     * alike get `-1`, `-2`, ... in depth-first order.
     */
    fun build(windows: List<Window>): Map<String, Node> {
        val byId = HashMap<String, Node>()
        val seen = HashMap<String, Int>()
        for (window in windows) {
            window.nodes = window.root?.let { prune(it) } ?: ArrayList()
            for (node in window.nodes) {
                label(node)
                assignIds(node, window.type + "\u0000" + window.packageName, "", "", seen, byId)
            }
        }
        return byId
    }

    private fun assignIds(
        node: Node,
        windowKey: String,
        taggedAncestor: String,
        clickableAncestor: String,
        seen: MutableMap<String, Int>,
        byId: MutableMap<String, Node>,
    ) {
        val key = windowKey + "\u0000" + taggedAncestor + "\u0000" + node.resourceId + "\u0000" + node.className +
            "\u0000" + node.label + "\u0000" + clickableAncestor
        val hash = "%08x".format(fnv1a(key))
        val n = seen[hash] ?: 0
        seen[hash] = n + 1
        node.id = if (n == 0) hash else "$hash-$n"
        byId[node.id] = node
        val tagged = if (node.resourceId.isEmpty()) taggedAncestor else node.resourceId
        val clickable = if (node.clickable) node.label else clickableAncestor
        for (child in node.children) assignIds(child, windowKey, tagged, clickable, seen, byId)
    }

    private fun fnv1a(s: String): Int {
        var h = 0x811c9dc5.toInt()
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193
        }
        return h
    }

    /** The state JSON for [windows], after [build]. */
    fun json(packageName: String?, activity: String?, focus: String?, windows: List<Window>): String {
        val out = StringBuilder(16_384)
        out.append("{\"package\":")
        string(out, packageName)
        out.append(",\"activity\":")
        string(out, activity)
        out.append(",\"focus\":")
        string(out, focus)
        out.append(",\"windows\":[")
        for ((i, w) in windows.withIndex()) {
            if (i > 0) out.append(',')
            out.append("{\"type\":")
            string(out, w.type)
            out.append(",\"package\":")
            string(out, w.packageName)
            out.append(",\"title\":")
            string(out, w.title)
            out.append(",\"active\":").append(w.active)
            out.append(",\"focused\":").append(w.focused)
            out.append(",\"layer\":").append(w.layer)
            bounds(out, w.left, w.top, w.right, w.bottom)
            out.append(",\"nodes\":")
            nodes(out, w.nodes)
            out.append('}')
        }
        return out.append("]}").toString()
    }

    private fun nodes(out: StringBuilder, nodes: List<Node>) {
        out.append('[')
        for ((i, n) in nodes.withIndex()) {
            if (i > 0) out.append(',')
            out.append("{\"id\":")
            string(out, n.id)
            out.append(",\"resourceId\":")
            string(out, n.resourceId)
            out.append(",\"class\":")
            string(out, n.className.substringAfterLast('.'))
            out.append(",\"text\":")
            string(out, n.text)
            out.append(",\"desc\":")
            string(out, n.desc)
            out.append(",\"label\":")
            string(out, n.label)
            out.append(",\"clickable\":").append(n.clickable)
            out.append(",\"longClickable\":").append(n.longClickable)
            out.append(",\"scrollable\":").append(n.scrollable)
            out.append(",\"enabled\":").append(n.enabled)
            out.append(",\"checkable\":").append(n.checkable)
            out.append(",\"checked\":").append(n.checked)
            out.append(",\"selected\":").append(n.selected)
            out.append(",\"focused\":").append(n.focused)
            out.append(",\"editable\":").append(n.editable)
            bounds(out, n.left, n.top, n.right, n.bottom)
            out.append(",\"children\":")
            nodes(out, n.children)
            out.append('}')
        }
        out.append(']')
    }

    private fun bounds(out: StringBuilder, left: Int, top: Int, right: Int, bottom: Int) {
        out.append(",\"bounds\":[").append(left).append(',').append(top).append(',').append(right).append(',')
            .append(bottom).append(']')
    }

    private fun string(out: StringBuilder, s: String?) {
        if (s == null) {
            out.append("null")
            return
        }
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}

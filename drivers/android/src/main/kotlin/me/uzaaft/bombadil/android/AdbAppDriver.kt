package me.uzaaft.bombadil.android

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.uzaaft.bombadil.BombadilDriver
import me.uzaaft.bombadil.runDriver

/** What the device shows: the focused window and the visible UI tree. */
data class Screen(
    /** Package of the focused app, or null if none. */
    val packageName: String?,
    /** Class of the focused app's activity, or null if none. */
    val activity: String?,
    /** Title of the focused window, e.g. `Application Error: <package>` for a crash dialog. */
    val focus: String?,
    val nodes: List<UiNode>,
) {
    fun toJson(lastAction: JsonElement): JsonElement = buildJsonObject {
        put("package", packageName)
        put("activity", activity)
        put("focus", focus)
        put("lastAction", lastAction)
        put("nodes", JsonArray(nodes.map { it.toJson() }))
    }

    companion object {
        private val focusPattern = Regex("""mCurrentFocus=Window\{\S+ \S+ ([^}]*)}""")
        private val appPattern = Regex("""mFocusedApp=ActivityRecord\{\S+ \S+ ([^/\s]+)/(\S+)""")

        /** Parses a `uiautomator dump` followed by `dumpsys window` output. */
        fun parse(output: String): Screen {
            val tail = output.substringAfterLast("</hierarchy>")
            val app = appPattern.find(tail)
            return Screen(
                packageName = app?.groupValues?.get(1),
                activity = app?.groupValues?.get(2)?.let { if (it.startsWith(".")) app.groupValues[1] + it else it },
                focus = focusPattern.find(tail)?.groupValues?.get(1),
                nodes = UiNode.parseDump(output).flatMap { it.prune() },
            )
        }
    }
}

/**
 * Drives an installed Android app over adb: the state is the focused
 * window and the visible UI tree, and actions are taps, text input,
 * swipes and key presses.
 *
 * Each session clears the app's data, launches it fresh, and reports
 * states once two consecutive UI dumps agree.
 *
 * Actions, externally tagged:
 * - `{"Tap": selector}` taps the centre of the node [selector] picks; see [select].
 * - `{"Tap": {"x": x, "y": y}}` taps a point.
 * - `{"LongPress": selector}` long-presses a node.
 * - `{"TypeText": {"text": "...", "into": selector}}` types into the
 *   focused field, after tapping the `into` node if given.
 * - `{"Scroll": {"direction": "up"|"down"|"left"|"right", "percent": 1..100}}`
 *   swipes so the content moves the given share of the node picked by
 *   the other selector fields (or of the largest scrollable node, or of
 *   the screen) in that direction.
 * - `{"Swipe": {"from": [x, y], "to": [x, y]}}`.
 * - `"Back"`, `"Home"`, `"Enter"`.
 *
 * A selector that matches nothing leaves the app alone, since replayed
 * or shrunk traces may refer to nodes that are no longer there.
 */
class AdbAppDriver(
    private val adb: Adb,
    private val packageName: String,
    /** Installed at the start of each session if given. */
    private val apk: String? = null,
    /** Permissions granted before launching, so their prompts don't block the app. */
    private val permissions: List<String> = emptyList(),
    /** Dumps per settle at most; the last one wins if no two agree. */
    private val maxDumps: Int = 4,
    override val stallTimeoutMs: Long? = 120_000,
) : BombadilDriver {
    override val name = "android-adb ($packageName on ${adb.serial})"

    private var screen: Screen? = null

    override fun start(): JsonElement {
        adb.run("wait-for-device", timeoutSeconds = 60)
        waitUntil(60_000, "boot") { adb.shell("getprop sys.boot_completed").trim() == "1" }
        for (setting in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) {
            adb.shell("settings put global $setting 0")
        }
        if (apk != null) adb.run("install", "-r", "-g", apk, timeoutSeconds = 120)
        adb.shell("am force-stop $packageName")
        adb.shell("pm clear $packageName").let { check("Success" in it) { "pm clear failed: $it" } }
        for (permission in permissions) adb.shell("pm grant $packageName $permission")
        val activity = adb.shell("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $packageName")
            .lines().map { it.trim() }.lastOrNull { it.startsWith("$packageName/") }
            ?: error("$packageName has no launcher activity; is it installed?")
        adb.shell("am start -W -n $activity")
        return settle(JsonNull)
    }

    override fun apply(action: JsonElement): JsonElement {
        val (tag, args) = when (action) {
            is JsonPrimitive -> action.content to JsonObject(emptyMap())
            is JsonObject -> action.entries.single().let { it.key to it.value.jsonObject }
            else -> error("not an action: $action")
        }
        when (tag) {
            "Tap" -> point(args)?.let { (x, y) -> adb.shell("input tap $x $y") }
            "LongPress" -> point(args)?.let { (x, y) -> adb.shell("input swipe $x $y $x $y 800") }
            "TypeText" -> {
                args["into"]?.jsonObject?.let { into -> point(into)?.let { (x, y) -> adb.shell("input tap $x $y") } }
                adb.shell("input text ${inputTextArgument(args.string("text") ?: error("TypeText without text"))}")
            }
            "Scroll" -> scroll(args)
            "Swipe" -> {
                val (x1, y1) = args.getValue("from").jsonArray.map { it.jsonPrimitive.int }
                val (x2, y2) = args.getValue("to").jsonArray.map { it.jsonPrimitive.int }
                adb.shell("input swipe $x1 $y1 $x2 $y2 300")
            }
            "Back" -> adb.shell("input keyevent KEYCODE_BACK")
            "Home" -> adb.shell("input keyevent KEYCODE_HOME")
            "Enter" -> adb.shell("input keyevent KEYCODE_ENTER")
            else -> error("unknown action `$tag`")
        }
        return settle(action)
    }

    override fun stop() {
        runCatching { adb.shell("am force-stop $packageName") }
    }

    private fun scroll(args: JsonObject) {
        val area = if (args.keys.any { it in selectorKeys }) select(args)?.bounds ?: return
        else largestScrollable() ?: screenBounds()
        val share = (args["percent"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 100) / 100.0
        // Keep clear of the area's edges, where system gestures and bars live.
        val left = area.left + (area.right - area.left) / 10
        val right = area.right - (area.right - area.left) / 10
        val top = area.top + (area.bottom - area.top) / 10
        val bottom = area.bottom - (area.bottom - area.top) / 10
        val dx = ((right - left) * share).toInt()
        val dy = ((bottom - top) * share).toInt()
        val (cx, cy) = area.centerX to area.centerY
        // Content moves with the finger, so scrolling down drags upwards.
        val (from, to) = when (args.string("direction") ?: "down") {
            "down" -> (cx to bottom) to (cx to bottom - dy)
            "up" -> (cx to top) to (cx to top + dy)
            "right" -> (right to cy) to (right - dx to cy)
            "left" -> (left to cy) to (left + dx to cy)
            else -> error("unknown scroll direction in $args")
        }
        adb.shell("input swipe ${from.first} ${from.second} ${to.first} ${to.second} 400")
    }

    private fun largestScrollable() =
        screen?.nodes.orEmpty().asSequence().flatMap { it.flatten() }.filter { it.scrollable }
            .map { it.bounds }.maxByOrNull { (it.right - it.left).toLong() * (it.bottom - it.top) }

    private fun screenBounds() =
        screen?.nodes?.map { it.bounds }?.reduceOrNull { a, b ->
            Bounds(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
        } ?: Bounds(0, 0, 1080, 2400)

    private fun point(args: JsonObject): Pair<Int, Int>? {
        val x = args["x"]?.jsonPrimitive?.intOrNull
        val y = args["y"]?.jsonPrimitive?.intOrNull
        if (x != null && y != null) return x to y
        val node = select(args) ?: return null
        return node.bounds.centerX to node.bounds.centerY
    }

    /**
     * The node in the last reported state whose `resourceId`, `text`,
     * `desc`, `label` and `class` equal those given (all optional),
     * taking the `index`th match (default 0) in depth-first order.
     */
    fun select(selector: JsonObject): UiNode? {
        val wanted = selector.filterKeys { it in selectorKeys }.mapValues { it.value.jsonPrimitive.content }
        val matches = screen?.nodes.orEmpty().asSequence().flatMap { it.flatten() }.filter { node ->
            wanted.all { (key, value) -> node.field(key) == value }
        }
        val node = matches.elementAtOrNull(selector["index"]?.jsonPrimitive?.intOrNull ?: 0)
        if (node == null) System.err.println("[$name] no node matches $selector; skipping")
        return node
    }

    private fun UiNode.field(key: String) = when (key) {
        "resourceId" -> resourceId
        "text" -> text
        "desc" -> desc
        "label" -> label
        "class" -> className.substringAfterLast('.')
        else -> null
    }

    /** Dumps until two consecutive dumps agree, or [maxDumps] times. */
    private fun settle(lastAction: JsonElement): JsonElement {
        var previous: Screen? = null
        for (attempt in 1..maxDumps) {
            val current = dump()
            if (current == previous) break
            previous = current
        }
        screen = previous
        return previous!!.toJson(lastAction)
    }

    private fun dump(): Screen {
        var failure: Exception? = null
        repeat(3) {
            try {
                return Screen.parse(
                    adb.shell(
                        "uiautomator dump /dev/tty; echo; dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'",
                        timeoutSeconds = 20,
                    ),
                )
            } catch (e: Exception) {
                // `uiautomator dump` fails now and then, e.g. "could not get idle state".
                failure = e
                Thread.sleep(300)
            }
        }
        throw IllegalStateException("UI dump failed", failure)
    }

    private fun waitUntil(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!runCatching(condition).getOrDefault(false)) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(500)
        }
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content

    private companion object {
        val selectorKeys = setOf("resourceId", "text", "desc", "label", "class")
    }
}

/**
 * Runs an [AdbAppDriver] for one Bombadil session.
 *
 * ```
 * bombadil-android --package <package> [--apk <file>] [--grant <permission>]...
 * ```
 *
 * The device is the first serial in `BOMBADIL_ANDROID_SERIALS`
 * (comma-separated) that no other driver on this host is using, so
 * parallel fuzzing workers each get their own emulator; it defaults to
 * `ANDROID_SERIAL`, then `emulator-5554`.
 */
fun main(args: Array<String>) {
    var packageName: String? = null
    var apk: String? = null
    val permissions = mutableListOf<String>()
    val rest = args.iterator()
    while (rest.hasNext()) {
        when (val arg = rest.next()) {
            "--package" -> packageName = rest.next()
            "--apk" -> apk = rest.next()
            "--grant" -> permissions += rest.next()
            else -> error("unknown argument `$arg`")
        }
    }
    val serials = (System.getenv("BOMBADIL_ANDROID_SERIALS") ?: System.getenv("ANDROID_SERIAL") ?: "emulator-5554")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    runDriver(
        AdbAppDriver(
            Adb.claim(serials),
            packageName ?: error("--package is required"),
            apk,
            permissions,
        ),
    )
}
